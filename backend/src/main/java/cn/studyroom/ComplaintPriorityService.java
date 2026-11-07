package cn.studyroom;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Immutable rule-set versions; empty/unmatched rules never guess an initial priority. */
@Service
public class ComplaintPriorityService {
    public record Classification(Integer priority,String status,long mappingVersion) {}
    private record Rule(String faultType,String impactScope,Integer priority,boolean enabled) {
        Map<String,Object> view(){var row=new LinkedHashMap<String,Object>();row.put("fault_type",faultType);row.put("impact_scope",impactScope);row.put("priority",priority);row.put("enabled",enabled);return row;}
    }
    private final Store db;private final AuthService auth;private final AuditService audit;private final ObjectMapper json;
    public ComplaintPriorityService(Store db,AuthService auth,AuditService audit,ObjectMapper json){this.db=db;this.auth=auth;this.audit=audit;this.json=json;}

    @Transactional(readOnly=true)
    public Map<String,Object> current(long actor,boolean includeDisabled){active(actor);var latest=db.one("SELECT version,mappings,actor_id,reason,created_at FROM complaint_priority_mapping_versions ORDER BY version DESC LIMIT 1");var rules=parse(latest.get("mappings"));if(!includeDisabled)rules=rules.stream().filter(Rule::enabled).toList();return projection(latest,rules,includeDisabled);}

    @Transactional
    public Map<String,Object> replace(long actor,Requests.ComplaintPriorityMappings request){
        db.lockBookings();requireAdmin(actor);Api.require(request!=null,400,"优先级映射请求必填");Api.require(request.version()!=null&&request.version()>0&&request.version()<Long.MAX_VALUE,400,"映射版本须为有效正整数");String operation=AttachmentService.uuid(request.operationId(),"operation_id");String reason=text(request.reason(),1000,true,"变更理由");List<Rule> rules=normalize(request.rules());String bodyHash=hash(actor,reason,rules);
        var prior=db.list("SELECT version,actor_id,request_hash FROM complaint_priority_mapping_versions WHERE operation_id=CAST(? AS UUID)",operation);
        if(!prior.isEmpty()){var saved=prior.get(0);Api.require(Store.id(saved,"actor_id")==actor&&Store.id(saved,"version")==request.version()+1&&bodyHash.equals(saved.get("request_hash")),409,"操作标识已用于其他映射内容");var version=db.one("SELECT version,mappings,actor_id,reason,created_at FROM complaint_priority_mapping_versions WHERE version=?",Store.id(saved,"version"));return projection(version,parse(version.get("mappings")),true);}
        var old=db.one("SELECT version,mappings,actor_id,reason,created_at FROM complaint_priority_mapping_versions ORDER BY version DESC LIMIT 1");Api.require(Store.id(old,"version")==request.version(),409,"映射配置已变化，请刷新后重试");
        long next=request.version()+1;String encoded=write(rules.stream().map(Rule::view).toList());db.update("INSERT INTO complaint_priority_mapping_versions(version,mappings,operation_id,request_hash,actor_id,reason,created_at) VALUES(?,?::jsonb,CAST(? AS UUID),?,?,?,CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')",next,encoded,operation,bodyHash,actor,reason);
        var after=db.one("SELECT version,mappings,actor_id,reason,created_at FROM complaint_priority_mapping_versions WHERE version=?",next);audit.record(actor,"complaint.priority_mapping.update","complaint_priority_mapping_versions",Long.toString(next),reason,Map.of("version",request.version(),"mappings",parse(old.get("mappings")).stream().map(Rule::view).toList()),Map.of("version",next,"mappings",rules.stream().map(Rule::view).toList()));return projection(after,rules,true);
    }

    @Transactional(readOnly=true)
    public Classification classify(String faultType,String impactScope,boolean safetyRisk,boolean unsafeArea){var latest=db.one("SELECT version,mappings FROM complaint_priority_mapping_versions ORDER BY version DESC LIMIT 1");long version=Store.id(latest,"version");if(safetyRisk||unsafeArea)return new Classification(5,"safety_override",version);String fault=normal(faultType),scope=normal(impactScope);for(var rule:parse(latest.get("mappings")))if(rule.enabled()&&rule.faultType().equalsIgnoreCase(fault)&&rule.impactScope().equalsIgnoreCase(scope))return new Classification(rule.priority(),"mapped",version);return new Classification(null,"unconfigured",version);}

    private List<Rule> normalize(List<Requests.ComplaintPriorityRule> input){Api.require(input!=null&&input.size()<=500,400,"映射规则最多500条且不能为空字段");var seen=new HashSet<String>();var out=new ArrayList<Rule>();for(var item:input){Api.require(item!=null,400,"映射规则无效");String fault=text(item.faultType(),100,true,"故障类型"),scope=text(item.impactScope(),100,true,"影响范围");Api.require(item.priority()!=null&&item.priority()>=1&&item.priority()<=4,400,"映射等级只允许1至4级；5级由安全规则固定产生");Api.require(item.enabled()!=null,400,"规则启停状态必填");String key=fault.toLowerCase(Locale.ROOT)+"\u0000"+scope.toLowerCase(Locale.ROOT);Api.require(seen.add(key),400,"同一故障类型和影响范围组合只能配置一条规则");out.add(new Rule(fault,scope,item.priority(),item.enabled()));}out.sort(Comparator.comparing(Rule::faultType).thenComparing(Rule::impactScope));return List.copyOf(out);}
    private List<Rule> parse(Object raw){try{String source=raw==null?"[]":raw.toString();var rows=json.readValue(source,new TypeReference<List<Map<String,Object>>>(){});var out=new ArrayList<Rule>();for(var row:rows)out.add(new Rule(normal(Objects.toString(row.get("fault_type"),"")),normal(Objects.toString(row.get("impact_scope"),"")),row.get("priority")==null?null:((Number)row.get("priority")).intValue(),Boolean.TRUE.equals(row.get("enabled"))));return List.copyOf(out);}catch(Exception e){throw new IllegalStateException("优先级映射快照不可读取",e);}}
    private Map<String,Object> projection(Map<String,Object> version,List<Rule> rules,boolean includeMeta){var result=new LinkedHashMap<String,Object>();result.put("version",Store.id(version,"version"));result.put("rules",rules.stream().map(Rule::view).toList());if(includeMeta){result.put("reason",version.get("reason"));result.put("actor_id",version.get("actor_id"));result.put("created_at",AttachmentService.offset(version.get("created_at")));}return result;}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("优先级映射不能序列化",e);}}
    private String hash(long actor,String reason,List<Rule> rules){try{var body=new LinkedHashMap<String,Object>();body.put("actor_id",actor);body.put("reason",reason);body.put("rules",rules.stream().map(Rule::view).toList());return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(write(body).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("优先级映射不能规范化",e);}}
    private String normal(String value){return Normalizer.normalize(Objects.toString(value,""),Normalizer.Form.NFC).trim();}
    private String text(String value,int max,boolean required,String label){String normalized=normal(value);Api.require((!required||!normalized.isEmpty())&&normalized.length()<=max,400,label+"不能为空或超长");return normalized;}
    private void active(long actor){Api.require("active".equals(auth.profile(actor).get("status")),403,"账号已停用");}
    private void requireAdmin(long actor){var user=auth.profile(actor);Api.require("admin".equals(user.get("role"))&&"active".equals(user.get("status")),403,"需要启用的管理员权限");}
}

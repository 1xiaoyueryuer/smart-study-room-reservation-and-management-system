import {ref} from 'vue'
import {validateEvidenceFile} from './evidenceFile.js'
export function useEvidenceUploadQueue({uploadFile,isLocked=()=>false,isActive=()=>true,setError=()=>{},validateFile=validateEvidenceFile,newKey=()=>crypto.randomUUID()}){
  const items=ref([]),uploading=ref(false);let alive=true,generation=0
  const current=()=>alive&&isActive()
  async function uploadItem(item){
    if(isLocked()||uploading.value||!items.value.includes(item))return
    if(items.value.length>3){item.error='请先明确移除多余照片，最多3张后再上传';return}
    const ticket=generation;const active=()=>current()&&ticket===generation
    uploading.value=true;item.phase='uploading';item.error='';item.metadata=null;let started=false
    try{await validateFile(item.file);if(!active())return;started=true;const response=await uploadFile(item.file);if(!active()||!items.value.includes(item))return
      const metadata=response.data
      if(!metadata?.id||!metadata.display_name||!['image/png','image/jpeg'].includes(metadata.content_type)||!metadata.size_bytes||!metadata.width||!metadata.height)throw new Error('上传未返回可核对的材料ID和信息')
      item.metadata=metadata;item.phase='uploaded'
    }catch(failure){if(active()){item.phase='error';item.error=started&&(failure.status==null||failure.status===408||failure.status>=500)?'上传结果未确认，未取得附件ID。请主动重试或移除此文件；未绑定暂存材料会按24小时规则清理。':failure.message}}
    finally{if(active())uploading.value=false}
  }
  async function selectFiles(event){const selected=Array.from(event.target.files||[]);event.target.value='';if(isLocked()||uploading.value)return
    const added=selected.map(file=>({key:newKey(),file,phase:'queued',metadata:null,error:''}));items.value.push(...added);setError('')
    if(items.value.length>3){setError('最多3张照片，所选文件项已保留，请明确移除多余文件后逐项重试上传');for(const item of added){item.phase='error';item.error='超过照片数量上限，尚未上传'};return}
    for(const item of added)await uploadItem(item)
  }
  function removeItem(item){if(!isLocked()&&!uploading.value)items.value=items.value.filter(value=>value!==item)}
  return{items,uploading,uploadItem,selectFiles,removeItem,reset(){generation++;items.value=[];uploading.value=false},dispose(){alive=false;generation++}}
}

import request from './request'
export const getOverview=(params={})=>request.get('/stats/overview',{params,silent:true})
export const getHeatmap=(roomId,date)=>request.get('/stats/heatmap',{params:{room_id:roomId,date},silent:true})

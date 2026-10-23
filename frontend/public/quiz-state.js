export const keywordMaxLength=30;
export const sourceLabel=value=>({ai:'AI 출제',mixed:'문제은행 + AI 출제',bank:'문제은행'}[value]||'문제 준비 중');

export function createRoomPayload(fields,profile){
 const keyword=String(fields.keyword||'').trim();
 if(!keyword||keyword.length>keywordMaxLength)throw Error(`퀴즈 키워드를 1~${keywordMaxLength}자로 입력해 주세요.`);
 const count=Number(fields.count),timeLimit=Number(fields.timeLimit),capacity=Number(fields.capacity);
 if(![4,6,8,10,12].includes(count)||![5,10,15,20,30].includes(timeLimit)||!Number.isInteger(capacity)||capacity<2||capacity>100)throw Error('문제 수, 제한 시간, 인원을 확인해 주세요.');
 return {keyword,title:String(fields.title||'').trim(),name:profile.name,avatar:profile.avatar,count,timeLimit,capacity,visibility:fields.visibility==='friends'?'friends':'public'};
}

export function preparation(room){
 if(room.prepare==='failed')return {state:'failed',title:'이번 출발선은 준비하지 못했어요.',detail:room.prepareError||'새로운 방을 만들어 다시 시도해 주세요.',canStart:false};
 if(room.prepare==='preparing')return {state:'preparing',title:'우리의 퀴즈를 준비하고 있어요.',detail:room.startRequested?'출발을 예약했어요. 준비가 끝나면 자동으로 시작해요.':'자료와 문제를 확인하는 중이에요. 먼저 입장해 채팅하고, 출발을 예약할 수 있어요.',canStart:!room.startRequested};
 const reduced=room.requestedCount>room.total;
 return {state:'ready',title:reduced?`${room.total}문제로 출발할 준비가 됐어요.`:'출발 준비 완료!',detail:reduced?`요청한 ${room.requestedCount}문제 중 검증을 통과한 ${room.total}문제로 진행해요.`:`${room.total}문제 · 문제당 ${room.timeLimit}초 · ${sourceLabel(room.source)}`,canStart:true};
}

export async function request(path,body,{token,signal}={}){
 const headers={};if(body!==undefined)headers['Content-Type']='application/json';if(token)headers.Authorization=`Bearer ${token}`;
 const response=await fetch(path,{method:body===undefined?'GET':'POST',headers,signal,cache:'no-store',...(body===undefined?{}:{body:JSON.stringify(body)})});
 let data;try{data=await response.json();}catch{throw Error('서버 응답을 읽을 수 없어요. API 연결 주소를 확인해 주세요.');}
 if(!response.ok)throw Object.assign(Error(data.error||'연결을 확인해 주세요.'),{status:response.status});return data;
}

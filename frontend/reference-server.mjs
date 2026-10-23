// Historical Node API implementation for regression tests; npm start uses the Spring proxy.
import http from 'node:http';
import {readFile} from 'node:fs/promises';
import {randomBytes, randomUUID} from 'node:crypto';
import {scoreAnswer} from './scoring.mjs';
import {questionCounts} from './public/topics.js';
import {makeQuestions,resolveTopic} from './questions.mjs';
import {parseAvatar} from './avatars.mjs';
import {clovaConfigured,generateQuestions} from './quiz-generator.mjs';
import {existsSync} from 'node:fs';
// Node 20.12+ can load local secrets without shipping a dotenv dependency.
if (existsSync(new URL('./.env', import.meta.url)) && process.loadEnvFile) process.loadEnvFile(new URL('./.env', import.meta.url));
const rooms = new Map();
const avatars = new Map();
let pendingGenerations = 0;
const colors = ['#d9ff43','#b9a7ff','#ff9b75','#91d7f3','#f3a7d6','#7bddb1'];
const publicPlayer = p => ({id:p.id,name:p.name,avatar:p.avatar,color:p.color,score:p.score,correct:p.correct,lastPoints:p.lastPoints,online:p.streams.size>0||Date.now()-(p.lastSeen||0)<5000,answered:p.answer!==null});
function state(r, p) {
 const q = r.questions[r.index];
 return {serverTime:Date.now(),code:r.code,title:r.title,keyword:r.keyword,questionSource:r.questionSource,capacity:r.capacity,phase:r.phase,index:r.index,total:r.questions.length,deadline:r.deadline,timeLimit:r.timeLimit,messages:r.messages,host:r.host,you:p.id,players:[...r.players.values()].map(publicPlayer),question:r.phase==='playing'||r.phase==='reveal'?{text:q.text,options:q.options}:null,answer:r.phase==='reveal'?q.answer:null,explanation:r.phase==='reveal'?q.explanation||null:null,yourAnswer:p.answer};
}
function publish(r) { for (const p of r.players.values()) for (const s of p.streams) s.write(`data: ${JSON.stringify(state(r,p))}\n\n`); }
function schedule(r, fn, ms) {clearTimeout(r.timer); r.timer=setTimeout(fn,ms);r.timer.unref();}
function start(r) {if(r.phase!=='waiting')return; r.phase='countdown';r.index=0;r.deadline=Date.now()+5000;publish(r);schedule(r,()=>next(r),5000);}
function next(r) {r.phase='playing';r.startedAt=Date.now();r.deadline=r.startedAt+r.timeLimit*1000;for(const p of r.players.values()){p.answer=null;p.answeredAt=null;p.lastPoints=0;}publish(r);schedule(r,()=>reveal(r),r.timeLimit*1000);}
function reveal(r) {if(r.phase!=='playing')return;r.phase='reveal';for(const p of r.players.values())if(p.answer===r.questions[r.index].answer){p.lastPoints=scoreAnswer(p.answeredAt-r.startedAt,r.timeLimit*1000);p.score+=p.lastPoints;p.correct++;}const duration=r.questions[r.index].explanation?8000:3500;r.deadline=Date.now()+duration;publish(r);schedule(r,()=>{r.index++;if(r.index>=r.questions.length){r.phase='finished';r.deadline=null;publish(r);}else next(r);},duration);}
async function body(req) {let s='';for await(const c of req){s+=c;if(s.length>100000)throw Error('요청이 너무 큽니다.');}const value=JSON.parse(s||'{}');if(!value||Array.isArray(value)||typeof value!=='object')throw Error('요청 형식을 확인해 주세요.');return value;}
function send(res,status,data){res.writeHead(status,{'Content-Type':'application/json'});res.end(JSON.stringify(data));}
function player(r, token){const p=[...r.players.values()].find(p=>p.token===token);if(!p)throw Error('다시 입장해 주세요.');return p;}
function saveAvatar(parsed){if(!parsed)return '';avatars.set(parsed.id,parsed.bytes);return `/api/avatars/${parsed.id}.jpg`;}
function addPlayer(r,name,avatar){const p={id:randomUUID(),token:randomBytes(24).toString('hex'),name:String(name||'러너').trim().slice(0,16)||'러너',avatar:saveAvatar(avatar),score:0,correct:0,lastPoints:0,answer:null,color:colors[r.players.size%colors.length],streams:new Set()};r.players.set(p.id,p);return p;}
const server=http.createServer(async(req,res)=>{
 try {
 const url=new URL(req.url,'http://localhost');
 if(url.pathname==='/api/health')return send(res,200,{ok:true});
 if(url.pathname==='/api/config'&&req.method==='GET')return send(res,200,{aiAvailable:clovaConfigured(),aiProvider:'CLOVA Studio',questionCounts});
 const avatarMatch=url.pathname.match(/^\/api\/avatars\/([a-f0-9]{64})\.jpg$/);
 if(avatarMatch&&req.method==='GET'){const bytes=avatars.get(avatarMatch[1]);if(!bytes)return send(res,404,{error:'사진을 찾을 수 없어요.'});res.writeHead(200,{'Content-Type':'image/jpeg','Cache-Control':'public, max-age=86400, immutable','X-Content-Type-Options':'nosniff'});return res.end(bytes);}
 if(url.pathname==='/api/rooms'&&req.method==='GET')return send(res,200,[...rooms.values()].filter(r=>r.phase==='waiting'&&r.visibility!=='friends').map(r=>({code:r.code,title:r.title,keyword:r.keyword,questionSource:r.questionSource,count:r.players.size,capacity:r.capacity,total:r.questions.length,timeLimit:r.timeLimit})));
 if(url.pathname==='/api/rooms'&&req.method==='POST'){
  const b=await body(req); const capacity=b.capacity===undefined?4:Number(b.capacity);if(!Number.isInteger(capacity)||capacity<2||capacity>100)throw Error('정원은 2~100명으로 설정해 주세요.'); const timeLimit=b.timeLimit===undefined?10:Number(b.timeLimit);if(![5,10,15,20,30].includes(timeLimit))throw Error('제한시간은 5·10·15·20·30초 중 선택해 주세요.');
  const visibility=b.visibility||'public';if(!['public','friends'].includes(visibility))throw Error('참가 방식을 확인해 주세요.');
  const avatar=parseAvatar(b.avatar);const questionSource=b.questionSource||'bank';if(!['bank','ai'].includes(questionSource))throw Error('출제 방식을 확인해 주세요.');
  const keyword=String(b.keyword||'').trim();if(!keyword||keyword.length>60)throw Error('퀴즈 키워드를 1~60자로 입력해 주세요.');
  const count=b.count===undefined?6:Number(b.count);if(!questionCounts.includes(count))throw Error('문제 수는 4·6·8·10·12개 중 선택해 주세요.');
  let questions;
  if(questionSource==='ai'){
   if(pendingGenerations>=3)throw Object.assign(Error('다른 퀴즈를 생성 중이에요. 잠시 후 다시 시도해 주세요.'),{status:429});
   pendingGenerations++;try{questions=await generateQuestions(keyword,count);}finally{pendingGenerations--;}
  }else questions=makeQuestions(keyword,count);
  let code;do{code=randomBytes(3).toString('hex').toUpperCase();}while(rooms.has(code));
  const r={code,title:String(b.title||`${keyword} 한 판 달려!`).slice(0,50),keyword:questionSource==='ai'?keyword:resolveTopic(keyword),questionSource,visibility,capacity:capacity,questions,timeLimit,messages:[],phase:'waiting',index:0,players:new Map(),created:Date.now()};
  const p=addPlayer(r,b.name,avatar);r.host=p.id;rooms.set(code,r);return send(res,201,{code,token:p.token});
 }
 const match=url.pathname.match(/^\/api\/rooms\/([A-Z0-9]{6})\/(join|events|state|start|answer|chat|profile)$/);
 if(match){
  const r=rooms.get(match[1]);if(!r)return send(res,404,{error:'방을 찾을 수 없어요. 코드를 확인해 주세요.'});
  const action=match[2];
  if(action==='state'&&req.method==='GET'){const p=player(r,req.headers.authorization?.replace(/^Bearer /,''));p.lastSeen=Date.now();return send(res,200,state(r,p));}
  if(action==='events'&&req.method==='GET'){
   const p=player(r,url.searchParams.get('token'));res.writeHead(200,{'Content-Type':'text/event-stream','Cache-Control':'no-cache','Connection':'keep-alive','X-Accel-Buffering':'no'});p.streams.add(res);publish(r);
   const keep=setInterval(()=>res.write(': heartbeat\n\n'),20000);req.on('close',()=>{clearInterval(keep);p.streams.delete(res);publish(r);});return;
  }
  if(req.method!=='POST')return send(res,405,{error:'지원하지 않는 요청입니다.'});
  const b=await body(req);
  if(action==='join'){
   if(r.phase!=='waiting')throw Error('이미 출발한 레이스예요. 새로운 방에 참여해 주세요.');
   if(r.players.size>=r.capacity)throw Error('정원이 가득 찼어요.');
   const p=addPlayer(r,b.name,parseAvatar(b.avatar));publish(r);if(r.players.size===r.capacity)start(r);return send(res,200,{code:r.code,token:p.token});
  }
  const p=player(r,b.token);
  if(action==='profile'){
   const avatar=parseAvatar(b.avatar);const name=String(b.name||'').trim();if(!name||name.length>16)throw Error('닉네임은 1~16자로 입력해 주세요.');
   p.name=name;p.avatar=saveAvatar(avatar);publish(r);return send(res,200,{ok:true});
  }
  if(action==='chat'){
   const text=typeof b.text==='string'?b.text.trim():'';if(!text||text.length>200)throw Error('메시지는 1~200자로 입력해 주세요.');
   if(Date.now()-(p.lastChat||0)<700)throw Error('잠깐만요! 조금 천천히 보내 주세요.');
   p.lastChat=Date.now();r.messages.push({id:randomUUID(),playerId:p.id,name:p.name,text,at:p.lastChat});if(r.messages.length>100)r.messages.shift();publish(r);
  }
  if(action==='start'){if(p.id!==r.host)throw Error('방장만 시작할 수 있어요.');start(r);}
  if(action==='answer'){
   if(r.phase!=='playing'||Date.now()>=r.deadline)throw Error('답변 시간이 끝났어요.');
   if(p.answer!==null)throw Error('이미 답변을 제출했어요.');
   if(!Number.isInteger(b.answer)||b.answer<0||b.answer>3)throw Error('답변을 선택해 주세요.');
   p.answer=b.answer;p.answeredAt=Date.now();publish(r);if([...r.players.values()].every(p=>p.answer!==null))reveal(r);
  }return send(res,200,{ok:true});
 }
 if(url.pathname.startsWith('/api/'))return send(res,404,{error:'찾을 수 없는 요청입니다.'});
 const files={'/':'index.html','/app.js':'app.js','/profile.js':'profile.js','/topics.js':'topics.js','/world.js':'world.js','/world.css':'world.css','/style.css':'style.css','/arcade.css':'arcade.css','/features.css':'features.css'};const file=files[url.pathname]||'index.html';const content=await readFile(new URL(`./public/${file}`,import.meta.url));res.writeHead(200,{'Content-Type':file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':'text/html'});res.end(content);
 }catch(e){send(res,e.status||400,{error:e.message});}
});
setInterval(()=>{for(const [code,r] of rooms)if(Date.now()-r.created>6*60*60*1000){clearTimeout(r.timer);for(const p of r.players.values())for(const s of p.streams)s.end();rooms.delete(code);}const used=new Set([...rooms.values()].flatMap(r=>[...r.players.values()].map(p=>p.avatar.split('/').at(-1)?.replace('.jpg',''))));for(const id of avatars.keys())if(!used.has(id))avatars.delete(id);},60000).unref();
server.listen(Number(process.env.PORT)||3000,'0.0.0.0',()=>console.log(`QUIZRUN ready at http://localhost:${process.env.PORT||3000}`));

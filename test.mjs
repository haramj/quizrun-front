import {test,before,after} from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {makeQuestions,topics,resolveTopic} from './questions.mjs';
let proc;const base='http://127.0.0.1:3198';
const post=async(path,body)=>{const r=await fetch(base+path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});return {status:r.status,data:await r.json()};};
before(async()=>{proc=spawn(process.execPath,['server.mjs'],{env:{...process.env,PORT:'3198'}});await new Promise((resolve,reject)=>{proc.stdout.once('data',resolve);proc.once('error',reject);proc.once('exit',()=>reject(Error('server exited')));});});
after(()=>proc.kill());
test('keyword bank validates topic and produces valid questions',()=>{for(const topic of ['상식','개발','과학','역사']){const q=makeQuestions(topic,6);assert.equal(q.length,6);assert.equal(new Set(q.map(x=>x.text)).size,6);assert.ok(q.every(x=>x.options.length===4&&x.answer>=0&&x.answer<4));}assert.throws(()=>makeQuestions('unsupported',6));});
async function snapshot(session){const abort=new AbortController();const res=await fetch(`${base}/api/rooms/${session.code}/events?token=${session.token}`,{signal:abort.signal});const reader=res.body.getReader();const {value}=await reader.read();abort.abort();return JSON.parse(new TextDecoder().decode(value).split('data: ')[1].split('\n')[0]);}
test('two users join, capacity starts shared race, private answers and host authorization',async()=>{
 const made=await post('/api/rooms',{keyword:'개발',name:'Host',capacity:2,count:4});assert.equal(made.status,201);const host=made.data;
 let state=await snapshot(host);assert.equal(state.phase,'waiting');assert.equal(state.question,null);assert.ok(!JSON.stringify(state).includes(host.token));
 const joined=await post(`/api/rooms/${host.code}/join`,{name:'Friend'});assert.equal(joined.status,200);const guest=joined.data;
 state=await snapshot(host);assert.equal(state.phase,'countdown');assert.equal(state.question,null);assert.equal((await post(`/api/rooms/${host.code}/answer`,{token:host.token,answer:0})).status,400);const countdown=state.deadline;assert.equal((await snapshot(guest)).deadline,countdown);await post(`/api/rooms/${host.code}/start`,{token:host.token});assert.equal((await snapshot(host)).deadline,countdown);await waitForPlaying(host);state=await snapshot(host);assert.equal(state.phase,'playing');assert.equal(state.players.length,2);assert.equal(state.answer,null);assert.equal(state.question.answer,undefined);
 assert.equal((await post(`/api/rooms/${host.code}/start`,{token:guest.token})).status,400);
 assert.equal((await post(`/api/rooms/${host.code}/answer`,{token:host.token,answer:9})).status,400);
 assert.equal((await post(`/api/rooms/${host.code}/answer`,{token:host.token,answer:0})).status,200);
 assert.equal((await post(`/api/rooms/${host.code}/answer`,{token:host.token,answer:0})).status,400);
 assert.equal((await post(`/api/rooms/${host.code}/answer`,{token:guest.token,answer:1})).status,200);
 state=await snapshot(guest);assert.equal(state.phase,'reveal');assert.ok(Number.isInteger(state.answer));assert.equal(state.yourAnswer,1);
 assert.equal((await post(`/api/rooms/${host.code}/join`,{name:'Late'})).status,400);
});
test('host can manually start before capacity',async()=>{const {data:s}=await post('/api/rooms',{keyword:'과학',name:'Solo',capacity:8,count:4});assert.equal((await post(`/api/rooms/${s.code}/start`,{token:s.token})).status,200);assert.equal((await snapshot(s)).phase,'countdown');await waitForPlaying(s);assert.equal((await snapshot(s)).phase,'playing');});

test('polling transport authenticates and exposes shared state without secret answers',async()=>{
 const {data:s}=await post('/api/rooms',{keyword:'상식',name:'Poll runner',capacity:4,count:4});
 const endpoint=`${base}/api/rooms/${s.code}/state`;
 assert.equal((await fetch(endpoint)).status,400);
 const state=await (await fetch(endpoint,{headers:{Authorization:`Bearer ${s.token}`}})).json();
 assert.equal(state.phase,'waiting');assert.equal(state.players[0].online,true);
 assert.ok(!JSON.stringify(state).includes(s.token));
 assert.equal((await fetch(`${base}/api/health`)).status,200);
});

test('new topics have eight valid unique questions',()=>{for(const topic of ['스포츠','영화','음악']){const qs=makeQuestions(topic,8);assert.equal(qs.length,8);assert.equal(new Set(qs.map(q=>q.text)).size,8);assert.ok(qs.every(q=>q.options.length===4&&q.options[q.answer]));}});
test('speed scoring rewards fast answers with bounded bonus',async()=>{const {scoreAnswer}=await import('./scoring.mjs');assert.equal(scoreAnswer(0,10000),1500);assert.equal(scoreAnswer(5000,10000),1250);assert.equal(scoreAnswer(10000,10000),1000);assert.ok(scoreAnswer(1000,10000)>scoreAnswer(8000,10000));});
test('custom time, authenticated chat, isolation and server-side speed scores',async()=>{
 assert.equal((await post('/api/rooms',{keyword:'음악',timeLimit:1})).status,400);
 const {data:host}=await post('/api/rooms',{keyword:'음악',capacity:3,count:4,timeLimit:5});
 const {data:guest}=await post(`/api/rooms/${host.code}/join`,{name:'guest'});
 assert.equal((await snapshot(host)).timeLimit,5);
 assert.equal((await post(`/api/rooms/${host.code}/chat`,{token:'wrong',text:'hi'})).status,400);
 assert.equal((await post(`/api/rooms/${host.code}/chat`,{token:host.token,text:'   '})).status,400);
 assert.equal((await post(`/api/rooms/${host.code}/chat`,{token:host.token,text:'x'.repeat(201)})).status,400);
 const message='<script>alert(1)</script> 안녕!';
 assert.equal((await post(`/api/rooms/${host.code}/chat`,{token:host.token,text:message})).status,200);
 assert.equal((await snapshot(guest)).messages[0].text,message);
 assert.equal((await post(`/api/rooms/${host.code}/chat`,{token:host.token,text:'spam'})).status,400);
 const {data:other}=await post('/api/rooms',{keyword:'스포츠'});assert.deepEqual((await snapshot(other)).messages,[]);assert.equal((await snapshot(other)).timeLimit,10);
 await post(`/api/rooms/${host.code}/start`,{token:host.token});
 await waitForPlaying(host);const q=await snapshot(host);assert.ok(q.deadline-Date.now()<=5000&&q.deadline-Date.now()>4000);
 const bankQuestion=makeQuestions('음악',12).find(x=>x.text===q.question.text);const answer=q.question.options.indexOf(bankQuestion.options[bankQuestion.answer]);
 await post(`/api/rooms/${host.code}/answer`,{token:host.token,answer,elapsedMs:0});
 await new Promise(r=>setTimeout(r,150));
 await post(`/api/rooms/${host.code}/answer`,{token:guest.token,answer,elapsedMs:0});
 const result=await snapshot(host);assert.equal(result.phase,'reveal');
 const first=result.players.find(p=>p.id===result.host),second=result.players.find(p=>p.id!==result.host);
 assert.ok(first.score>second.score);assert.ok(first.score<=1500&&second.score>=1000);assert.equal(first.correct,1);assert.equal(first.lastPoints,first.score);
});

async function waitForPlaying(session){const end=Date.now()+7000;while(Date.now()<end){const s=await snapshot(session);if(s.phase==='playing')return;await new Promise(resolve=>setTimeout(resolve,50));}assert.fail('Countdown did not start the game');}

test('all 15 categories contain 12 distinct valid questions and stable answers after shuffling',()=>{
 assert.equal(topics.length,15);const texts=new Set();
 for(const topic of topics){
  const first=makeQuestions(topic,12);assert.equal(first.length,12);assert.equal(new Set(first.map(q=>q.text)).size,12);
  for(const q of first){assert.ok(q.text.trim());assert.equal(q.options.length,4);assert.equal(new Set(q.options).size,4);assert.ok(Number.isInteger(q.answer)&&q.answer>=0&&q.answer<4);assert.ok(!texts.has(q.text));texts.add(q.text);}
  for(let pass=0;pass<4;pass++)for(const q of makeQuestions(topic,12)){const original=first.find(x=>x.text===q.text);assert.equal(q.options[q.answer],original.options[original.answer]);}
  assert.equal(resolveTopic(topic),topic);
 }
 assert.equal(texts.size,180);assert.equal(resolveTopic('영어'),'언어');assert.equal(resolveTopic('축구'),'스포츠');assert.throws(()=>makeQuestions('수학',13));
});
test('100 player capacity, full-room rejection and twelve-question creation',async()=>{
 for(const capacity of [1,101,2.5,'invalid'])assert.equal((await post('/api/rooms',{keyword:'환경',capacity})).status,400);
 const {data:host,status}=await post('/api/rooms',{keyword:'환경',capacity:100,count:12});assert.equal(status,201);
 for(let i=2;i<=99;i++)assert.equal((await post(`/api/rooms/${host.code}/join`,{name:`runner ${i}`})).status,200);
 let state=await snapshot(host);assert.equal(state.players.length,99);assert.equal(state.phase,'waiting');assert.equal(state.total,12);
 assert.equal((await post(`/api/rooms/${host.code}/join`,{name:'runner 100'})).status,200);
 state=await snapshot(host);assert.equal(state.players.length,100);assert.equal(state.capacity,100);assert.equal(state.phase,'countdown');
 assert.equal((await post(`/api/rooms/${host.code}/join`,{name:'runner 101'})).status,400);
 const list=await (await fetch(base+'/api/rooms')).json();assert.ok(!list.some(r=>r.code===host.code));
});

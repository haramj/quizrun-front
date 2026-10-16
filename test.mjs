import {test,before,after} from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {makeQuestions} from './questions.mjs';
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
 state=await snapshot(host);assert.equal(state.phase,'playing');assert.equal(state.players.length,2);assert.equal(state.answer,null);assert.equal(state.question.answer,undefined);
 assert.equal((await post(`/api/rooms/${host.code}/start`,{token:guest.token})).status,400);
 assert.equal((await post(`/api/rooms/${host.code}/answer`,{token:host.token,answer:9})).status,400);
 assert.equal((await post(`/api/rooms/${host.code}/answer`,{token:host.token,answer:0})).status,200);
 assert.equal((await post(`/api/rooms/${host.code}/answer`,{token:host.token,answer:0})).status,400);
 assert.equal((await post(`/api/rooms/${host.code}/answer`,{token:guest.token,answer:1})).status,200);
 state=await snapshot(guest);assert.equal(state.phase,'reveal');assert.ok(Number.isInteger(state.answer));assert.equal(state.yourAnswer,1);
 assert.equal((await post(`/api/rooms/${host.code}/join`,{name:'Late'})).status,400);
});
test('host can manually start before capacity',async()=>{const {data:s}=await post('/api/rooms',{keyword:'과학',name:'Solo',capacity:8,count:4});assert.equal((await post(`/api/rooms/${s.code}/start`,{token:s.token})).status,200);assert.equal((await snapshot(s)).phase,'playing');});

test('polling transport authenticates and exposes shared state without secret answers',async()=>{
 const {data:s}=await post('/api/rooms',{keyword:'상식',name:'Poll runner',capacity:4,count:4});
 const endpoint=`${base}/api/rooms/${s.code}/state`;
 assert.equal((await fetch(endpoint)).status,400);
 const state=await (await fetch(endpoint,{headers:{Authorization:`Bearer ${s.token}`}})).json();
 assert.equal(state.phase,'waiting');assert.equal(state.players[0].online,true);
 assert.ok(!JSON.stringify(state).includes(s.token));
 assert.equal((await fetch(`${base}/api/health`)).status,200);
});

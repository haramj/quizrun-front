import {test} from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {spawn} from 'node:child_process';
import {generateQuestions,validateQuestions} from './quiz-generator.mjs';

const questions = count => Array.from({length:count},(_,i)=>({text:`테스트 주제의 ${i+1}번째 문제는?`,options:[`정답 ${i+1}`,'오답 A','오답 B','오답 C'],answer:0,explanation:`${i+1}번 문제의 정답에 관한 해설입니다.`}));
const complete = qs => ({status:{code:'20000'},result:{finishReason:'stop',message:{role:'assistant',content:JSON.stringify({questions:qs})}}});
const env = {CLOVA_STUDIO_API_KEY:'test-only-key'};

test('CLOVA requests structured output for the exact count and preserves correct answers while shuffling',async()=>{
 for(const count of [4,6,8,10,12]){
  const output=await generateQuestions('공룡의 진화',count,{env,fetchImpl:async(url,request)=>{
   assert.equal(url,'https://clovastudio.stream.ntruss.com/v3/chat-completions/HCX-007');assert.equal(request.headers.Authorization,'Bearer test-only-key');
   const body=JSON.parse(request.body);assert.deepEqual(JSON.parse(body.messages[1].content),{keyword:'공룡의 진화',count});
   assert.equal(body.responseFormat.schema.properties.questions.minItems,count);assert.equal(body.responseFormat.schema.properties.questions.maxItems,count);
   assert.equal(request.redirect,'error');assert.ok(request.signal instanceof AbortSignal);
   return {ok:true,json:async()=>complete(questions(count))};
  }});
  assert.equal(output.length,count);output.forEach((q,i)=>{assert.equal(q.options[q.answer],`정답 ${i+1}`);assert.ok(q.explanation);});
 }
});

test('AI output validation rejects wrong counts, duplicates, invalid answers and incomplete explanations',()=>{
 assert.throws(()=>validateQuestions({questions:questions(3)},4));
 for(const change of [q=>q[0].answer=4,q=>q[0].answer='0',q=>q[0].options.pop(),q=>q[0].options[1]=q[0].options[0],q=>q[0].text='',q=>q[1].text=q[0].text,q=>q[0].explanation='',q=>q[0].explanation='x'.repeat(501)]){
  const qs=questions(4);change(qs);assert.throws(()=>validateQuestions({questions:qs},4));
 }
});

test('provider failures, truncation and timeouts return useful errors without leaking secrets',async()=>{
 await assert.rejects(generateQuestions('우주',4,{env:{}}),e=>e.status===503);
 await assert.rejects(generateQuestions('우주',5,{env}),/문제 수/);
 for(const status of [401,429,500])await assert.rejects(generateQuestions('우주',4,{env,fetchImpl:async()=>({ok:false,status})}),e=>e.status===502&&!e.message.includes(env.CLOVA_STUDIO_API_KEY));
 await assert.rejects(generateQuestions('우주',4,{env,fetchImpl:async()=>{throw Object.assign(Error('upstream secret'),{name:'TimeoutError'});}}),/시간/);
 for(const data of [{status:{code:'20000'},result:{finishReason:'length'}},{status:{code:'20000'},result:{finishReason:'stop',message:{content:'bad JSON'}}}]){
  await assert.rejects(generateQuestions('우주',4,{env,fetchImpl:async()=>({ok:true,json:async()=>data})}),/완성된 퀴즈/);
 }
});

test('AI rooms use generated questions, hide answers until reveal and reject malformed generation atomically',async()=>{
 const provider=http.createServer(async(req,res)=>{
  assert.equal(req.url,'/v3/chat-completions/HCX-007');assert.equal(req.headers.authorization,'Bearer test-only-key');
  let raw='';for await(const chunk of req)raw+=chunk;const input=JSON.parse(JSON.parse(raw).messages[1].content);
  const qs=questions(input.count);if(input.keyword==='중복 오류')qs[1].text=qs[0].text;
  res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify(complete(qs)));
 });
 await new Promise(resolve=>provider.listen(0,'127.0.0.1',resolve));
 const child=spawn(process.execPath,['server.mjs'],{env:{...process.env,...env,CLOVA_STUDIO_BASE_URL:`http://127.0.0.1:${provider.address().port}`,PORT:'3197'}});
 try{
  await new Promise((resolve,reject)=>{child.stdout.once('data',resolve);child.once('error',reject);child.once('exit',()=>reject(Error('server exited')));});
  const base='http://127.0.0.1:3197';
  const post=async(path,body)=>{const res=await fetch(base+path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});return {status:res.status,data:await res.json()};};
  const state=async session=>await(await fetch(`${base}/api/rooms/${session.code}/state`,{headers:{Authorization:`Bearer ${session.token}`}})).json();
  const config=await(await fetch(base+'/api/config')).json();assert.equal(config.aiAvailable,true);assert.ok(!JSON.stringify(config).includes('test-only-key'));
  const made=await post('/api/rooms',{keyword:'공룡의 진화',questionSource:'ai',count:12,capacity:4,timeLimit:5,name:'AI 러너'});assert.equal(made.status,201);
  const session=made.data;let room=await state(session);assert.equal(room.total,12);assert.equal(room.keyword,'공룡의 진화');assert.equal(room.questionSource,'ai');assert.equal(room.question,null);assert.equal(room.explanation,null);
  const bad=await post('/api/rooms',{keyword:'중복 오류',questionSource:'ai',count:4});assert.equal(bad.status,400);
  const list=await(await fetch(base+'/api/rooms')).json();assert.equal(list.length,1);assert.equal(list[0].questionSource,'ai');assert.ok(!('questions' in list[0]));
  assert.equal((await post(`/api/rooms/${session.code}/start`,{token:session.token})).status,200);
  const deadline=Date.now()+7000;do{room=await state(session);if(room.phase==='playing')break;await new Promise(resolve=>setTimeout(resolve,40));}while(Date.now()<deadline);
  assert.equal(room.phase,'playing');assert.equal(room.explanation,null);assert.equal(room.answer,null);assert.deepEqual(Object.keys(room.question).sort(),['options','text']);
  const answer=room.question.options.indexOf('정답 1');assert.ok(answer>=0);
  await post(`/api/rooms/${session.code}/answer`,{token:session.token,answer});room=await state(session);
  assert.equal(room.phase,'reveal');assert.equal(room.answer,answer);assert.ok(room.explanation.includes('1번'));assert.ok(room.players[0].score>=1000);
 }finally{child.kill();await new Promise(resolve=>provider.close(resolve));}
});

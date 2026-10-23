import {test,after} from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {createFrontendServer} from './server.mjs';
const servers=[];
async function listen(server){servers.push(server);await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));return `http://127.0.0.1:${server.address().port}`;}
after(async()=>{await Promise.all(servers.map(server=>new Promise(resolve=>{server.close(resolve);server.closeAllConnections();})));});

test('frontend forwards Spring auth, typed JSON, errors and cached JPEGs without a second game state',async()=>{
 const calls=[],photo=Buffer.from([255,216,1,2,255,217]);
 const backend=await listen(http.createServer(async(req,res)=>{
  let body='';for await(const chunk of req)body+=chunk;calls.push({url:req.url,method:req.method,auth:req.headers.authorization,body});
  if(req.url.startsWith('/api/avatars/')){res.writeHead(200,{'Content-Type':'image/jpeg','Cache-Control':'public, max-age=21600, immutable'});res.end(photo);return;}
  if(req.url.endsWith('/answer')){res.writeHead(400,{'Content-Type':'application/json'});res.end(JSON.stringify({error:'이미 답변을 제출했어요.'}));return;}
  res.writeHead(req.method==='POST'?201:200,{'Content-Type':'application/json'});res.end(JSON.stringify(req.method==='POST'?{code:'ABC123',token:'session'}:{prepare:'preparing',total:12,source:null}));
 }));
 const front=await listen(createFrontendServer({backendUrl:backend}));
 let response=await fetch(front+'/api/rooms',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({count:12,name:'러너'})});assert.equal(response.status,201);
 response=await fetch(front+'/api/rooms/ABC123/state?view=current',{headers:{Authorization:'Bearer session'}});assert.equal((await response.json()).prepare,'preparing');
 assert.equal(calls[1].auth,'Bearer session');assert.equal(calls[1].url,'/api/rooms/ABC123/state?view=current');assert.equal(JSON.parse(calls[0].body).count,12);
 response=await fetch(front+'/api/rooms/ABC123/answer',{method:'POST',headers:{'Content-Type':'application/json'},body:'{"token":"session","answer":1}'});
 assert.equal(response.status,400);assert.match((await response.json()).error,/이미 답변/);
 response=await fetch(front+'/api/avatars/test.jpg');assert.equal(response.headers.get('content-type'),'image/jpeg');assert.match(response.headers.get('cache-control'),/immutable/);assert.deepEqual(Buffer.from(await response.arrayBuffer()),photo);
 response=await fetch(front+'/app.js');assert.equal(response.status,200);assert.match(response.headers.get('content-type'),/javascript/);
 assert.equal((await fetch(front+'/.env')).status,404);assert.equal((await fetch(front+'/missing.js')).status,404);
 response=await fetch(front+'/api/rooms',{method:'POST',body:'x'.repeat(100001)});assert.equal(response.status,413);assert.equal(calls.length,4);
});

test('backend timeouts and outages return a friendly 502 rather than a false successful response',async()=>{
 const stalled=await listen(http.createServer(()=>{}));
 const front=await listen(createFrontendServer({backendUrl:stalled,timeoutMs:50}));
 const response=await fetch(front+'/api/rooms');assert.equal(response.status,502);assert.match((await response.json()).error,/백엔드/);
});

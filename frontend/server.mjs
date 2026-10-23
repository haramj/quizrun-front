import http from 'node:http';
import https from 'node:https';
import {readFile} from 'node:fs/promises';
import {existsSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

const publicRoot=path.resolve(fileURLToPath(new URL('./public/',import.meta.url)));
const types={'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.jpg':'image/jpeg','.png':'image/png','.svg':'image/svg+xml','.webp':'image/webp','.ico':'image/x-icon'};
const hopHeaders=new Set(['host','connection','transfer-encoding','keep-alive','proxy-authenticate','proxy-authorization','te','trailer','upgrade']);
const headersForProxy=headers=>Object.fromEntries(Object.entries(headers).filter(([key])=>!hopHeaders.has(key.toLowerCase())));
function json(res,status,error){if(res.destroyed||res.writableEnded)return;if(!res.headersSent){res.writeHead(status,{'Content-Type':'application/json; charset=utf-8'});res.end(JSON.stringify({error}));}else res.destroy();}

/** Serve the frontend and forward its API requests to Spring without owning game state. */
export function createFrontendServer({backendUrl='http://127.0.0.1:3000',timeoutMs=15000}={}){
 const backend=new URL(backendUrl);
 if(!['http:','https:'].includes(backend.protocol)||backend.username||backend.password||backend.search||backend.hash)throw Error('BACKEND_URL must be an HTTP(S) backend address without credentials or a query.');
 return http.createServer(async(req,res)=>{
  try{
   const incoming=new URL(req.url,'http://frontend.local');
   if(incoming.pathname.startsWith('/api/')){
    const chunks=[];let size=0;
    for await(const chunk of req){size+=chunk.length;if(size>100000){json(res,413,'요청이 너무 커요. 사진을 다시 선택해 주세요.');return;}chunks.push(chunk);}
    const payload=Buffer.concat(chunks),target=new URL(backend);
    target.pathname=backend.pathname.replace(/\/$/,'')+incoming.pathname;target.search=incoming.search;
    const headers=headersForProxy(req.headers);delete headers['content-length'];
    if(payload.length)headers['content-length']=String(payload.length);
    const upstream=(target.protocol==='https:'?https:http).request(target,{method:req.method,headers},response=>{
     res.writeHead(response.statusCode,headersForProxy(response.headers));response.on('error',()=>res.destroy());response.pipe(res);
    });
    upstream.setTimeout(timeoutMs,()=>upstream.destroy(Error('timeout')));
    upstream.on('error',()=>json(res,502,'백엔드에 연결할 수 없어요. 서버 실행 상태를 확인해 주세요.'));
    res.on('close',()=>{if(!res.writableEnded)upstream.destroy();});
    upstream.end(payload);return;
   }
   if(!['GET','HEAD'].includes(req.method)){json(res,405,'지원하지 않는 요청입니다.');return;}
   const requested=decodeURIComponent(incoming.pathname==='/'?'/index.html':incoming.pathname);
   const file=path.resolve(publicRoot,'.'+requested),type=types[path.extname(file)];
   if(!type||requested.includes('\\')||file.indexOf(publicRoot+path.sep)!==0){json(res,404,'찾을 수 없는 파일입니다.');return;}
   const bytes=await readFile(file);res.writeHead(200,{'Content-Type':type,'X-Content-Type-Options':'nosniff'});res.end(req.method==='HEAD'?undefined:bytes);
  }catch(error){json(res,error.code==='ENOENT'?404:400,error.code==='ENOENT'?'찾을 수 없는 파일입니다.':'요청을 처리할 수 없어요.');}
 });
}

if(process.argv[1]&&pathToFileURL(path.resolve(process.argv[1])).href===import.meta.url){
 if(existsSync(new URL('./.env',import.meta.url))&&process.loadEnvFile)process.loadEnvFile(new URL('./.env',import.meta.url));
 const port=Number(process.env.PORT)||5173,backendUrl=process.env.BACKEND_URL||'http://127.0.0.1:3000',backend=new URL(backendUrl);
 if(['localhost','127.0.0.1','0.0.0.0','[::1]'].includes(backend.hostname)&&Number(backend.port||(backend.protocol==='https:'?443:80))===port)throw Error('The frontend and backend must use different ports.');
 createFrontendServer({backendUrl}).listen(port,'0.0.0.0',()=>console.log(`QUIZRUN frontend ready at http://localhost:${port}`));
}

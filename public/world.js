// The same little stadium appears from the first visit to the finish line.
const images = new Map();
const motions = new Map();
let race, serverOffset = 0, shotAt = 0, disposeScene = () => {};
const reduced = matchMedia('(prefers-reduced-motion: reduce)');
const clamp = value => Math.max(0, Math.min(1, value));

export function updateWorldState(next, offset = 0) {
  serverOffset = offset;
  const now = performance.now();
  if (race?.code !== next.code) { motions.clear(); shotAt = 0; }
  if (next.phase === 'countdown') shotAt = next.deadline;
  else if (next.index === 0 && next.phase === 'playing') shotAt = next.deadline - next.timeLimit * 1000;
  for (const p of next.players) {
    const to = clamp(p.score / (next.total * 1500));
    const old = motions.get(p.id);
    if (!old) motions.set(p.id, {from: to, to, at: now, boost: false});
    else if (old.to !== to) {
      const progress = clamp((now - old.at) / 1200);
      motions.set(p.id, {from: old.from + (old.to - old.from) * (1 - (1 - progress) ** 3), to, at: now, boost: to > old.to});
    }
  }
  race = next;
}

export function stopWorldScenes() { disposeScene(); disposeScene = () => {}; }

export function mountWorldScenes(root, {profile = () => ({}), group = 0, groupSize = 6} = {}) {
  stopWorldScenes();
  const canvases = [...root.querySelectorAll('canvas[data-art]')];
  if (!canvases.length) return;
  const probe = document.createElement('span');
  probe.className = 'palette-probe';
  root.append(probe);
  const C = {};
  for (const key of ['paper','panel','ink','sub','line','grass','grass-top','grass-side','red','track','track-side','lane','purple','yellow','skin','hair','shadow','white']) {
    probe.style.color = `var(--${key})`;
    C[key] = getComputedStyle(probe).color;
  }
  probe.remove();
  let frame = 0, last = 0, disposed = false;
  const pendingImages = new Set();
  function photo(url) {
    if (!url) return null;
    if (!images.has(url)) {
      const img = new Image();
      img.src = url;
      images.set(url, img);
      if (images.size > 120) images.delete(images.keys().next().value);
    }
    const img = images.get(url);
    if (img && !img.complete && !pendingImages.has(url)) {
      pendingImages.add(url);
      img.addEventListener('load', () => { if (!disposed) paint(); }, {once:true});
    }
    return img?.complete && img.naturalWidth ? img : null;
  }
  function face(c, x, y, r, index, avatar) {
    circle(c, x, y, r + 2, C.lane);
    const image = photo(avatar);
    if (image) {
      c.save(); c.beginPath(); c.arc(x,y,r,0,Math.PI*2); c.clip();
      c.drawImage(image,x-r,y-r,r*2,r*2); c.restore(); return;
    }
    circle(c,x,y,r,C.skin);
    c.save(); c.beginPath(); c.arc(x,y,r,0,Math.PI*2); c.clip();
    ellipse(c,x-r*.12,y-r*.76,r*1.1,r*.48,C.hair);
    if (index%3===1) { c.fillStyle=C.hair; c.fillRect(x-r,y-r*.4,r*.22,r*.9); }
    c.restore();
    circle(c,x-r*.25,y-r*.02,r*.067,C.hair); circle(c,x+r*.29,y-r*.02,r*.067,C.hair);
    line(c,[[x-r*.13,y+r*.35],[x+r*.18,y+r*.36]],C.hair,Math.max(1,r*.065));
  }
  function runner(c,x,y,scale,index,pose='stand',t=0,boost=false,avatar='',mine=false) {
    c.save(); c.translate(x,y); c.scale(scale,scale);
    ellipse(c,0,4,26,6,C.shadow);
    const running=pose==='run', crouch=pose==='crouch';
    const stride=Math.sin(t*(boost?22:14)+index);
    const bob=running&&!reduced.matches?Math.abs(Math.sin(t*(boost?22:14)+index))*3:0;
    c.translate(0,-bob);
    const hip=[-1,crouch?-19:-33], sh=[crouch?11:4,crouch?-33:-55];
    const head=[crouch?25:9,crouch?-55:-77];
    const feet=crouch?[[-25,0],[12,0]]:running?[[-12+stride*20,-Math.max(0,stride)*13],[10-stride*20,-Math.max(0,-stride)*13]]:[[-12,0],[12,0]];
    for (const f of feet) {
      line(c,[hip,[f[0]*.5,-16],f],C.hair,8);
      c.fillStyle=C.white; c.fillRect(f[0]-5,f[1]-3,13,6);
    }
    line(c,[hip,sh],C.hair,25);
    line(c,[hip,sh],[C.purple,C.yellow,C.skin,C.grass][index%4],21);
    let hand1=crouch?[28,-2]:running?[22+stride*10,-39]:[23,-36];
    const hand2=crouch?[16,-2]:running?[-10-stride*10,-47]:[-17,-38];
    if (pose==='wave') hand1=[39,-88];
    line(c,[[sh[0]-6,sh[1]+3],hand2],C.skin,7);
    line(c,[[sh[0]+6,sh[1]+3],hand1],C.skin,7);
    c.fillStyle=C.white; c.fillRect(hip[0]-5,hip[1]-14,11,10);
    c.fillStyle=C.hair; c.font='700 8px sans-serif'; c.textAlign='center'; c.fillText(index+1,hip[0]+.5,hip[1]-6);
    face(c,...head,20,index,avatar);
    if (mine) {
      path(c,[[head[0]-5,head[1]-30],[head[0]+5,head[1]-30],[head[0],head[1]-24]],C.white);
    }
    if (boost) { line(c,[[-40,-20],[-28,-20]],C.lane,2); line(c,[[-45,-30],[-30,-30]],C.lane,2); }
    c.restore();
  }
  function tree(c,x,y,scale) {
    ellipse(c,x,y,18*scale,5*scale,C.shadow);
    line(c,[[x,y],[x,y-29*scale]],C['grass-side'],5*scale);
    ellipse(c,x,y-33*scale,16*scale,23*scale,C['grass-side']);
    ellipse(c,x-3*scale,y-38*scale,13*scale,19*scale,C['grass-top']);
  }
  function ground(c,w,h,active) {
    const mobile=w<560;
    const P=active?(mobile?(u,v)=>[w*(.04+.92*u),h*(.25-.06*u+.66*v)]:(u,v)=>[w*(.045+.78*u+.13*v),h*(.34-.16*u+.59*v)]):(u,v)=>[w*(.07+.71*u+.2*v),h*(.48-.24*u+.43*v)];
    const a=P(0,0), b=P(1,0), d=P(0,1), e=P(1,1);
    path(c,[a,b,[b[0],b[1]+10],[a[0],a[1]+10]],C['grass-side']);
    path(c,[d,e,[e[0],e[1]+13],[d[0],d[1]+13]],C['grass-side']);
    path(c,[a,b,e,d],C['grass-top']); return P;
  }
  function track(c,P,count=4,active=false) {
    const start=.07,end=.95,top=active?.09:.18,bottom=active?.9:.77;
    const a=P(start,top),b=P(end,top),d=P(start,bottom),e=P(end,bottom);
    path(c,[d,e,[e[0],e[1]+6],[d[0],d[1]+6]],C['track-side']); path(c,[a,b,e,d],C.track);
    for(let i=0;i<=count;i++)line(c,[P(start,top+(bottom-top)*i/count),P(end,top+(bottom-top)*i/count)],C.lane,1.6);
    line(c,[P(start+.08,top),P(start+.08,bottom)],C.lane,2.3);
    line(c,[P(end-.055,top),P(end-.055,bottom)],C.lane,3);
    const T=(u,lane)=>P(start+(end-start)*u,top+(bottom-top)*(lane+.72)/count);
    for(let i=0;i<count;i++) { const p=T(.025,i); c.save(); c.fillStyle=C.lane; c.font='600 11px sans-serif'; c.textAlign='center'; c.fillText(group*groupSize+i+1,...p); c.restore(); }
    return T;
  }
  function flags(c,P) {
    for(const [u,v] of [[.04,.06],[.94,.05]]) {
      const p=P(u,v); line(c,[p,[p[0],p[1]-35]],C.hair,2);
      path(c,[[p[0],p[1]-35],[p[0]+24,p[1]-31],[p[0],p[1]-20]],C.lane);
    }
  }
  function bench(c,x,y,scale) {
    c.save();c.translate(x,y);c.scale(scale,scale);
    path(c,[[-28,0],[18,-12],[29,-6],[-17,7]],C.panel);
    line(c,[[-20,4],[-20,15]],C.hair,3);line(c,[[23,-5],[23,6]],C.hair,3);c.restore();
  }
  function starter(c,x,y,scale,elapsed) {
    c.save();c.translate(x,y);c.scale(scale,scale);
    c.fillStyle=C.red;c.fillRect(-13,0,31,10);
    line(c,[[-7,0],[-5,-28],[2,-51],[12,-27],[13,0]],C.hair,6);
    line(c,[[2,-51],[2,-75]],C.white,16);line(c,[[5,-73],[23,-94],[23,-111]],C.skin,5);
    face(c,0,-92,10,2);
    c.fillStyle=C.hair;c.fillRect(20,-132,7,23);c.fillRect(26,-117,6,9);
    c.fillStyle=C.red;c.fillRect(20,-133,7,5);
    if(elapsed>=0&&elapsed<2400) {
      const t=reduced.matches?.35:elapsed/2400;
      // Keep the puff inside the stadium on narrow screens as it spreads from the muzzle.
      for(let i=0;i<10;i++) { c.globalAlpha=(1-t)*.72;circle(c,24+Math.cos(i*2.4)*(5+t*35),-125+t*18+Math.sin(i*2.4)*(4+t*10),6+t*14,C.white); }
      c.globalAlpha=1;
      c.fillStyle=C.ink;c.font='800 20px sans-serif';c.textAlign='center';c.fillText('탕!',60,-150);
    }
    c.restore();
  }
  function label(c,x,y,p,boost,width) {
    const name=`${p.id===race.you?'나 · ':''}${p.name}`;
    c.font='600 11px "Noto Sans KR",sans-serif';
    let display=name;
    while(c.measureText(display).width>Math.min(105,width*.3))display=display.slice(0,-2)+'…';
    const mobile=width<560;
    if(mobile){x+=x>width*.57?-68:68;y-=54;}
    const text=`${display}  ${p.score.toLocaleString()}`;
    const tw=c.measureText(text).width+14;
    x=Math.max(tw/2+2,Math.min(width-tw/2-2,x));
    c.fillStyle=p.id===race.you?C.ink:C.panel;c.beginPath();c.roundRect(x-tw/2,y+7,tw,23,8);c.fill();
    c.fillStyle=p.id===race.you?C.paper:C.ink;c.textAlign='center';c.fillText(text,x,y+23);
    if(boost) {c.fillStyle=C.lane;c.font='700 12px sans-serif';c.fillText(`+${p.lastPoints}`,x+20,y-(mobile?38:90));}
  }
  function paintScene(c,w,h,kind,now) {
    c.clearRect(0,0,w,h);
    const own=profile();
    if(kind==='profile') {
      ellipse(c,w*.5,h*.84,w*.3,24,C.shadow);ellipse(c,w*.5,h*.81,w*.30,25,C['grass-side']);ellipse(c,w*.5,h*.78,w*.30,24,C['grass-top']);
      runner(c,w*.48,h*.79,Math.min(w/150,h/145),0,'wave',0,false,own.avatar);return;
    }
    const active=kind==='race'||kind==='waiting';
    const players=active&&race?race.players.slice(group*groupSize,group*groupSize+groupSize):[];
    const count=active?Math.max(4,players.length):4;
    const P=ground(c,w,h,active),T=track(c,P,count,active);
    flags(c,P);tree(c,...P(.025,.2),.5);tree(c,...P(.96,.98),.55);bench(c,...P(.58,.025),.75);
    if(!active) {
      for(let i=0;i<(kind==='ticket'?3:4);i++)runner(c,...T(.22+i*.14,i),kind==='ticket'?.4:Math.max(.53,Math.min(.85,w/700)),i,i===0?'wave':'stand',0,false,i===0?own.avatar:'');
      const po=P(.78,.94);c.fillStyle=C.ink;c.font='700 11px "Space Grotesk",sans-serif';c.textAlign='center';c.fillText('QUIZRUN',...po);return;
    }
    const pose=race.phase==='countdown'?'crouch':['playing','reveal'].includes(race.phase)?'run':race.phase==='finished'?'wave':'stand';
    for(let i=0;i<players.length;i++) {
      const p=players[i], m=motions.get(p.id), progress=clamp((now-(m?.at||0))/1200);
      const distance=m?(reduced.matches?m.to:m.from+(m.to-m.from)*(1-(1-progress)**3)):0;
      const boost=Boolean(m?.boost&&progress<1);
      const pos=T(.12+.7*distance,i);
      runner(c,...pos,w<560?.82:1.0,group*groupSize+i,pose,reduced.matches?0:now/1000,boost,p.avatar,p.id===race.you);
      label(c,pos[0],pos[1],p,boost,w);
    }
    if(kind==='race')starter(c,...P(.77,.03),w<560?.53:.65,shotAt?Date.now()+serverOffset-shotAt:-1);
  }
  function paint(now=performance.now()) {
    if(disposed||document.hidden)return;
    for(const canvas of canvases) {
      const r=canvas.getBoundingClientRect();if(!r.width||!r.height)continue;
      const d=Math.min(devicePixelRatio||1,2), w=Math.round(r.width*d), h=Math.round(r.height*d);
      if(canvas.width!==w||canvas.height!==h) {canvas.width=w;canvas.height=h;}
      const ctx=canvas.getContext('2d');ctx.setTransform(d,0,0,d,0,0);paintScene(ctx,r.width,r.height,canvas.dataset.art,now);
    }
  }
  const active=canvases.some(c=>c.dataset.art==='race')&&race?.phase!=='finished';
  function tick(now) {
    if(disposed)return;
    if(now-last>32) {paint(now);last=now;}
    if(active&&!document.hidden)frame=requestAnimationFrame(tick);
  }
  function resume() {cancelAnimationFrame(frame);paint();if(active&&!document.hidden)frame=requestAnimationFrame(tick);}
  const observer=new ResizeObserver(()=>paint());canvases.forEach(c=>observer.observe(c));
  document.addEventListener('visibilitychange',resume);
  reduced.addEventListener('change',resume);
  resume();
  disposeScene=()=>{disposed=true;cancelAnimationFrame(frame);observer.disconnect();document.removeEventListener('visibilitychange',resume);reduced.removeEventListener('change',resume);};
  return paint;
}

function circle(c,x,y,r,color){c.fillStyle=color;c.beginPath();c.arc(x,y,r,0,Math.PI*2);c.fill();}
function ellipse(c,x,y,rx,ry,color){c.fillStyle=color;c.beginPath();c.ellipse(x,y,rx,ry,0,0,Math.PI*2);c.fill();}
function path(c,pts,color){c.beginPath();pts.forEach((p,i)=>i?c.lineTo(...p):c.moveTo(...p));c.closePath();c.fillStyle=color;c.fill();}
function line(c,pts,color,width=2){c.beginPath();pts.forEach((p,i)=>i?c.lineTo(...p):c.moveTo(...p));c.strokeStyle=color;c.lineWidth=width;c.lineCap='round';c.lineJoin='round';c.stroke();}

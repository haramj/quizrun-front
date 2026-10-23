import {topics, questionCounts} from './topics.js';
import {avatarMarkup, getProfile, saveProfile, profileEditorMarkup, mountProfileEditor} from './profile.js';
import {mountWorldScenes, stopWorldScenes, updateWorldState} from './world.js';

const $ = selector => document.querySelector(selector);
const smallScreen=matchMedia('(max-width:580px)');
const trackSize=()=>smallScreen.matches?4:6;
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let screen='home',room,source,profileEditor,profileNext='home',returnScreen='home';
let selectedRoomCode='',roomList=[],filter='전체',submitting=false,answering=false;
let serverOffset=0,toastTimer,trackGroup=0,chatPanel,chatRoom='',profilePaint,profilePhoto='',renderedPhase='';
let config={aiAvailable:false};
const draft={keyword:'',title:'',count:6,timeLimit:10,capacity:4,visibility:'public',questionSource:''};
const configReady=api('/api/config').then(data=>{config=data;}).catch(()=>{});
try{document.documentElement.dataset.theme=localStorage.getItem('quizrun-theme')||'auto';}catch{}

async function api(path,body){
 const response=await fetch(path,body?{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}:{});
 const data=await response.json();if(!response.ok)throw Error(data.error||'연결을 확인해 주세요.');return data;
}
function toast(message){$('#toast').textContent=message;$('#toast').classList.add('visible');clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('#toast').classList.remove('visible'),3500);}
function header(){const p=getProfile();return `<header class="top"><button class="logo" data-action="home" aria-label="퀴즈런 홈">QUIZ<b>RUN</b></button><nav aria-label="메인 메뉴"><button class="navlink" data-action="home" ${screen==='home'?'aria-current="page"':''}>플레이</button><button class="navlink" data-action="profile" ${screen==='profile'?'aria-current="page"':''}>내 캐릭터</button></nav><div class="top-end"><button class="theme-button" data-action="theme" aria-label="밝은 화면과 어두운 화면 전환">◐</button><button class="mini-profile" data-action="profile"><span class="mini-face">${avatarMarkup(p.avatar,p.name)}</span><span>${esc(p.name||'나의 러너')}</span></button></div></header>`;}
function footer(){return '<footer class="footer"><strong>ONE TRACK. EVERYONE.</strong><span>정답 하나, 한 걸음 더. 함께 달리는 퀴즈런.</span></footer>';}
function scene(kind,className,label){return `<div class="${className}"><canvas data-art="${kind}" role="img" aria-label="${label}"></canvas></div>`;}
function crumb(label,action='home'){return `<div class="crumb"><button data-action="${action}">← ${action==='back-profile'?'돌아가기':'플레이'}</button><span>/</span><span>${label}</span></div>`;}
function mountArt(){
 if(screen==='profile')profilePaint=mountWorldScenes($('#app'),{profile:()=>({avatar:profilePhoto})});
 else mountWorldScenes($('#app'),{profile:()=>getProfile(),group:trackGroup,groupSize:trackSize()});
}
function page(html,{focus=true}={}){
 profileEditor?.dispose();profileEditor=null;stopWorldScenes();
 const chat=$('#chat-panel');if(chat){chat.remove();chatPanel=chat;}
 $('#app').innerHTML=header()+`<main id="main-content" class="content">${html}</main>`+footer();mountArt();
 document.body.dataset.screen=screen;
 if(focus){window.scrollTo(0,0);const h=$('h1');if(h){h.tabIndex=-1;h.focus({preventScroll:true});}}
}
function home(){
 source?.close();source=null;room=null;screen='home';trackGroup=0;
 page(`<section class="hero"><div class="hero-copy"><div class="small-label">A SMALL WORLD OF QUIZ & RUN</div><h1>누구나 같은<br><em>출발선</em>에서.</h1><p>좋아하는 주제로 모이고, 정답만큼 앞으로.<br>내 얼굴을 한 러너와 작은 퀴즈 세계로 떠나요.</p><div class="hero-actions"><button class="primary" data-action="create">내 레이스 만들기 ↗</button><button class="secondary" data-action="join">초대 코드로 참가 →</button></div></div><div class="stadium-hero"><canvas data-art="hero" role="img" aria-label="잔디 위 빨간 육상 트랙에 모인 네 명의 토이 러너"></canvas><span class="scene-note">A LITTLE TRACK. A BIG GOOD TIME.</span></div></section><section class="intro-strip" aria-label="플레이 방법"><span><b>01</b> 내 얼굴로 참가</span><span><b>02</b> 좋아하는 주제 고르기</span><span><b>03</b> 정답만큼 앞으로</span></section><section class="open-rooms"><div class="section-line"><div><span class="small-label">OPEN TRACKS</span><h2>함께 달릴 레이스 <span id="room-count">0</span></h2></div><button class="text-button" data-action="refresh">새로고침 ↻</button></div><div class="room-tools"><div class="filters" aria-label="주제 필터">${['전체',...topics].map(t=>`<button data-filter="${esc(t)}" aria-pressed="${filter===t}">${t}</button>`).join('')}</div><label class="search"><span class="sr-only">방 검색</span><input id="search" class="input" placeholder="주제나 방 이름 검색" type="search"></label></div><div class="rooms" id="rooms"><p class="empty-rooms">출발선을 살펴보고 있어요…</p></div></section>`);loadRooms();
}
async function loadRooms(){try{roomList=await api('/api/rooms');if(screen==='home')renderRooms();}catch(error){if(screen==='home'){$('#rooms').innerHTML='<p class="empty-rooms">레이스를 불러오지 못했어요. 새로고침을 눌러 주세요.</p>';toast(error.message);}}}
function renderRooms(){
 if(!$('#rooms'))return;const query=$('#search').value.trim().toLowerCase();
 const list=roomList.filter(r=>(filter==='전체'||r.keyword.includes(filter))&&`${r.title} ${r.keyword}`.toLowerCase().includes(query));$('#room-count').textContent=roomList.length;
 $('#rooms').innerHTML=list.length?list.map((r,i)=>`<article class="room"><span class="lane-ticket">${String(i+1).padStart(2,'0')}</span><div><h3>${esc(r.title)}</h3><p>${esc(r.keyword)} · ${r.total}문제 · ${r.timeLimit}초${r.questionSource==='ai'?' · AI 출제':''}</p></div><div class="room-count"><span class="dots" aria-hidden="true"><i></i><i></i><i></i></span>${r.count}/${r.capacity}명</div><button class="secondary" data-room="${r.code}" aria-label="${esc(r.title)} 참가">참가 →</button></article>`).join(''):`<div class="empty-rooms"><span class="empty-flag" aria-hidden="true">⚑</span><h3>${query||filter!=='전체'?'아직 이 주제의 레이스가 없어요.':'오늘의 첫 출발선을 열어볼까요?'}</h3><p>주제 하나면 준비 끝. 친구들을 초대해 함께 달려요.</p><button class="secondary" data-action="create">레이스 만들기 ↗</button></div>`;
}
function begin(target,code=''){selectedRoomCode=code;if(!getProfile().name.trim()){profileNext=target;returnScreen='home';showProfile();}else if(target==='create')showCreate();else showJoin(code);}
function showProfile(){
 screen='profile';profilePhoto=getProfile().avatar;
 page(`<section class="page">${crumb('내 캐릭터','back-profile')}<div class="page-title"><div><h1>출발선에 설 당신.</h1><p>얼굴은 크게, 몸은 가볍게. 나만의 러너를 만들어 주세요.</p></div><span class="step-tag">YOUR RUNNER</span></div><div class="profile-grid"><div class="profile-visual">${scene('profile','character-stage','큰 얼굴과 보라색 유니폼을 가진 내 러너')}<p class="character-caption"><b data-runner-label>${esc(getProfile().name||'나의 러너')}</b>오늘도 함께 달릴 준비 완료.</p></div><form id="profile-form" class="profile-form"><label class="field"><span class="field-label">러너 이름 <small>최대 16자</small></span><input class="input" name="name" required maxlength="16" autocomplete="nickname" placeholder="친구들이 부를 이름" value="${esc(getProfile().name)}"></label>${profileEditorMarkup()}<p class="hint-note">내 사진이 러너의 얼굴이 돼요.<br>원을 맞춰 자르고, 0.5배까지 작게 조절할 수 있어요.</p><p class="error" role="alert"></p><div class="profile-actions"><button type="button" class="text-button" data-action="back-profile">← 돌아가기</button><button class="primary" type="submit">${['home','room'].includes(profileNext)?'프로필 저장':'저장하고 계속'} →</button></div></form></div></section>`);
 const form=$('#profile-form');profileEditor=mountProfileEditor(form,{onChange:value=>{profilePhoto=value;profilePaint?.();}});
 form.elements.name.addEventListener('input',()=>{$('[data-runner-label]').textContent=form.elements.name.value||'나의 러너';});
 form.onsubmit=async event=>{
  event.preventDefault();if(submitting)return;const name=form.elements.name.value.trim();
  try{
   if(!name)throw Error('함께 달릴 이름을 입력해 주세요.');const avatar=profileEditor.getAvatar();setBusy(form,true);
   if(room)await api(`/api/rooms/${room.code}/profile`,{token:token(),name,avatar});
   const saved=saveProfile({name,avatar});setBusy(form,false);
   if(room){const me=room.players.find(p=>p.id===room.you);if(me){me.name=name;me.avatar=avatar;}updateWorldState(room,serverOffset);}
   if(profileNext==='create')showCreate();else if(profileNext==='join')showJoin(selectedRoomCode);else if(profileNext==='room'&&room)renderRoom();else home();
   toast(saved?'내 러너를 저장했어요.':'러너를 적용했어요. 이 브라우저에서는 다음 접속까지 사진을 저장할 수 없어요.');
  }catch(error){form.querySelector('.error').textContent=error.message;setBusy(form,false);}
 };
}
function segments(name,values,selected,suffix=''){return `<div class="segments">${values.map(value=>`<label class="segment"><input type="radio" name="${name}" value="${value}" ${value===Number(selected)?'checked':''}><span>${value}${suffix}</span></label>`).join('')}</div>`;}
function showJoin(code=''){
 screen='join';selectedRoomCode=code;
 page(`<section class="page">${crumb('초대 코드 참가')}<div class="page-title"><div><h1>친구들이 기다리고 있어요.</h1><p>같은 출발선에 서는 데 필요한 건, 코드 하나.</p></div></div><div class="join-layout">${scene('join','join-scene','친구들을 기다리는 작은 육상 경기장')}<form id="join-form" class="join-form"><label class="field"><span class="field-label">6자리 초대 코드</span><input class="input code-input" name="code" value="${esc(code)}" required pattern="[A-Za-z0-9]{6}" maxlength="6" autocomplete="off" autocapitalize="characters" spellcheck="false" placeholder="ABC123"></label><button type="button" class="ticket-profile" data-action="edit-join-profile"><span class="mini-face">${avatarMarkup(getProfile().avatar,getProfile().name)}</span><span>${esc(getProfile().name||'내 러너 설정하기')}</span><small>수정 ↗</small></button><p class="error" role="alert"></p><button class="primary" type="submit">같은 출발선으로 →</button><p class="help">링크로 초대받았다면 코드가 자동으로 입력돼요.</p><div class="hint-note">아직 초대받은 레이스가 없나요?<br><button class="text-button" type="button" data-action="home">열려 있는 레이스 둘러보기 ↗</button></div></form></div></section>`);
 const form=$('#join-form');form.onsubmit=async event=>{
  event.preventDefault();if(submitting)return;selectedRoomCode=form.elements.code.value.trim().toUpperCase();
  if(!getProfile().name.trim()){profileNext='join';returnScreen='join';showProfile();return;}
  try{setBusy(form,true);const session=await api(`/api/rooms/${selectedRoomCode}/join`,getProfile());setBusy(form,false);enter(session);}
  catch(error){setBusy(form,false);form.querySelector('.error').textContent=error.message;}
 };
}
function setBusy(form,value){
 submitting=value;form.setAttribute('aria-busy',String(value));
 form.querySelectorAll('input,select,button').forEach(el=>{el.disabled=value;});
 document.querySelectorAll('header button').forEach(el=>{el.disabled=value;});
}
function token(){return sessionStorage.getItem(`quizrun-${room.code}`);}
function enter(session){sessionStorage.setItem(`quizrun-${session.code}`,session.token);history.pushState({},'',`/?room=${session.code}`);connect(session.code,session.token);}
function connect(code,auth){
 source?.close();screen='room';room=null;trackGroup=0;renderedPhase='';
 page('<section class="page loading-state"><h1>출발선으로 가는 중…</h1><p>러너와 레이스를 연결하고 있어요.</p></section>');
 if(chatRoom!==code){chatPanel=null;chatRoom=code;}
 let stopped=false,last='',pollTimer,failures=0,first=true;
 source={close(){stopped=true;clearTimeout(pollTimer);}};
 async function poll(){
  try{
   const response=await fetch(`/api/rooms/${code}/state`,{headers:{Authorization:`Bearer ${auth}`},cache:'no-store'});
   const data=await response.json();if(stopped)return;
   if(!response.ok){source.close();history.replaceState({},'','/');home();toast(data.error||'다시 입장해 주세요.');return;}
   failures=0;serverOffset=data.serverTime-Date.now();
   const serialized=JSON.stringify({...data,messages:undefined,serverTime:undefined});updateWorldState(data,serverOffset);room=data;
   if(first){trackGroup=Math.floor(data.players.findIndex(p=>p.id===data.you)/trackSize());first=false;}
   if(last!==serialized){last=serialized;if(screen==='room')renderRoom();}renderChat(data);
  }catch{if(++failures===3)toast('연결을 다시 확인하고 있어요…');}
  if(!stopped)pollTimer=setTimeout(poll,700);
 }
 poll();
}
function trackControls(){
 const size=trackSize();if(room.players.length<=size)return '';
 return `<div class="track-controls"><label>보고 있는 트랙 <select id="track-group" aria-label="트랙 참가자 그룹">${Array.from({length:Math.ceil(room.players.length/size)},(_,i)=>`<option value="${i}" ${i===trackGroup?'selected':''}>${i*size+1}–${Math.min(room.players.length,i*size+size)}번 러너</option>`).join('')}</select></label><button class="text-button" data-action="my-track">내 러너 보기 ↗</button></div>`;
}
function stadium(waiting=false){
 const size=trackSize(),count=Math.max(4,room.players.slice(trackGroup*size,trackGroup*size+size).length);
 return `<div class="${waiting?'gathering':'race-scene'} stadium" style="--lanes:${count}"><canvas data-art="${waiting?'waiting':'race'}" role="img" aria-label="${waiting?'출발을 기다리는':'점수에 따라 전진하는'} ${room.players.length}명의 러너. 참가자별 이름과 점수는 아래 목록에서 확인할 수 있어요."></canvas><span class="gather-tag">${waiting?'MEET AT THE START':'QUIZRUN ATHLETICS'}</span>${room.phase==='countdown'?'<div class="race-countdown" id="countdown-number" role="status" aria-live="polite">준비!</div>':''}${!waiting?'<span class="start-signal" id="start-signal" role="status" hidden>탕! · 출발</span>':''}<span class="scene-note">${waiting?'ONE TRACK. EVERYONE.':'START → FINISH'}</span></div>${trackControls()}`;
}
function roomHeading(){return `<div class="wait-heading"><div><span class="small-label">${room.phase==='waiting'?'MEET YOUR RUNNERS':room.phase==='finished'?'THE FINISH LINE':'LIVE QUIZ RACE'}</span><h1>${esc(room.title)}</h1><p>${esc(room.keyword)} · ${room.total}문제 · 문제당 ${room.timeLimit}초 · ${room.questionSource==='ai'?'CLOVA AI 출제':'문제은행'}</p></div><div class="invite"><div><small>초대 코드</small><code>${room.code}</code></div><button data-action="copy-code" aria-label="초대 코드 복사">⧉</button><button data-action="invite" aria-label="초대 링크 복사">↗</button></div></div>`;}
function leaderboard(){
 const ranking=[...room.players].sort((a,b)=>b.score-a.score);
 return `<section class="leaderboard"><div class="section-line"><div><span class="small-label">THE LEADERBOARD</span><h2>함께 달리는 러너</h2></div><span class="help">${room.players.length}/${room.capacity}명</span></div><div class="rank-list">${ranking.map(p=>`<div class="rank-row ${p.id===room.you?'is-me':''}"><span class="rank-num">${1+ranking.filter(other=>other.score>p.score).length}</span><span class="mini-face">${avatarMarkup(p.avatar,p.name)}</span><div class="rank-name"><b>${esc(p.name)}${p.id===room.you?' <small>나</small>':''}</b><small>${p.id===room.host?'방장 · ':''}${p.answered?'답변 완료':p.online?'접속 중':'연결 대기'}</small></div><strong>${p.score.toLocaleString()}<small>점</small></strong></div>`).join('')}</div><p class="help">정답 1,000점 + 스피드 최대 500점 · 동점이면 공동 순위</p></section>`;
}
function question(){
 const r=room,me=r.players.find(p=>p.id===r.you);
 if(r.phase==='countdown')return '<section class="start-note"><h2>제자리에. 준비…</h2><p>신호총이 울리면 첫 번째 문제가 열려요.</p></section>';
 if(r.phase==='finished'){
  const ranking=[...r.players].sort((a,b)=>b.score-a.score),winners=ranking.filter(p=>p.score===ranking[0].score);
  return `<section class="results"><span class="small-label">GOOD RACE, EVERYONE!</span><h2>${esc(ranking[0].name)}${winners.length>1?` 외 ${winners.length-1}명 공동`:''} 우승!</h2><p>나의 기록 <b>${me.score.toLocaleString()}점</b> · ${me.correct}/${r.total} 정답 · ${1+ranking.filter(p=>p.score>me.score).length}위</p><button class="primary" data-action="new">한 번 더 달리기 ↗</button></section>`;
 }
 return `<section class="question-panel"><div class="question-meta"><span>QUESTION ${String(r.index+1).padStart(2,'0')} <small>/ ${r.total}</small></span><span><span class="timer" id="timer">${r.timeLimit}s</span><small> ${r.phase==='reveal'?'다음 출발까지':'남았어요'}</small></span></div><div class="time-track"><div id="time-bar"></div></div><div class="quiz-question"><div><h2>${esc(r.question.text)}</h2><p class="help">정답 하나, 한 걸음 더.<br>빠르게 맞힐수록 더 멀리 달려요.</p></div><div class="quiz-answers">${r.question.options.map((option,i)=>`<button class="quiz-answer ${r.yourAnswer===i?'chosen':''} ${r.phase==='reveal'&&r.answer===i?'correct':''} ${r.phase==='reveal'&&r.yourAnswer===i&&r.answer!==i?'wrong':''}" data-answer="${i}" ${r.phase!=='playing'||r.yourAnswer!==null||answering?'disabled':''}><b>${'ABCD'[i]}</b><span>${esc(option)}</span>${r.phase==='reveal'&&r.answer===i?'<strong aria-label="정답">✓</strong>':''}</button>`).join('')}</div></div><p class="quiz-result" role="status">${r.phase==='reveal'?(r.yourAnswer===r.answer?`정답! +${me.lastPoints.toLocaleString()}점 · 스피드 보너스 +${me.lastPoints-1000}점. 내 러너가 치고 나갑니다!`:`다음 문제에서 따라잡아요! 정답은 ${'ABCD'[r.answer]}입니다.`):r.yourAnswer!==null?'답변 완료! 다른 러너들을 기다리고 있어요.':`${r.timeLimit}초 안에 정답을 선택해 주세요.`}</p>${r.phase==='reveal'&&r.explanation?`<div class="answer-explanation"><b>정답 해설</b><p>${esc(r.explanation)}</p></div>`:''}</section>`;
}
function renderRoom(){
 screen='room';const r=room,waiting=r.phase==='waiting',host=r.host===r.you;
 const entering=!renderedPhase||renderedPhase==='waiting'&&r.phase==='countdown';renderedPhase=r.phase;
 const focused=$('#chat-input')===document.activeElement,selection=focused?[$('#chat-input').selectionStart,$('#chat-input').selectionEnd]:null;
 const size=trackSize(),focusedAnswer=document.activeElement?.dataset.answer,visible=r.players.slice(trackGroup*size,trackGroup*size+size);
 page(`<section class="page room-page">${roomHeading()}${waiting?`<div class="wait-grid"><div>${stadium(true)}<div class="participants">${visible.map(p=>`<div class="participant"><span class="mini-face">${avatarMarkup(p.avatar,p.name)}</span><div><b>${esc(p.name)} ${p.id===r.you?'· 나':''}</b><small>${p.id===r.host?'방장 · ':''}${p.online?'출발 준비':'연결 대기'}</small></div></div>`).join('')}${Array.from({length:Math.min(size-visible.length,r.capacity-r.players.length)},()=>'<div class="participant empty"><span class="mini-face">+</span>친구를 기다리는 자리</div>').join('')}</div></div><div id="chat-slot"></div></div><div class="wait-bottom"><p><b>${r.players.length}명</b>이 같은 출발선에 있어요.<br>정원 ${r.capacity}명이 모이면 자동으로 출발해요.</p><div class="wait-actions"><button class="secondary" data-action="profile">내 러너 수정</button>${host?'<button class="primary" data-action="start">레이스 시작 →</button>':'<span class="help" role="status">방장의 출발 신호를 기다리고 있어요.</span>'}</div></div>`:`${stadium()}${question()}<div class="race-social">${leaderboard()}<div id="chat-slot"></div></div>`}</section>`,{focus:false});
 mountChat();renderChat(r);updateClock();
 if(entering)window.scrollTo(0,0);
 if(focused){$('#chat-input').focus({preventScroll:true});$('#chat-input').setSelectionRange(...selection);}
 if(focusedAnswer!==undefined){const button=$(`[data-answer="${focusedAnswer}"]`);if(button&&!button.disabled)button.focus({preventScroll:true});}
}
function mountChat(){
 const slot=$('#chat-slot');if(!slot)return;if(chatPanel){slot.append(chatPanel);return;}
 slot.innerHTML=`<section class="chat" id="chat-panel"><h2>러너들의 수다 <span class="chat-live">● LIVE</span></h2><div id="chat-messages" class="messages" role="log" aria-live="polite" aria-label="방 채팅"></div><form id="chat-form" class="chat-form"><input class="input" id="chat-input" aria-label="채팅 메시지" maxlength="200" placeholder="가볍게 인사해 보세요!" required autocomplete="off"><button type="submit" aria-label="메시지 전송">↑</button></form><p id="chat-error" class="error" role="status"></p></section>`;chatPanel=$('#chat-panel');
 $('#chat-form').onsubmit=async event=>{
  event.preventDefault();const input=$('#chat-input'),text=input.value.trim();if(!text||!room)return;const button=event.target.querySelector('button');button.disabled=true;
  try{await api(`/api/rooms/${room.code}/chat`,{token:token(),text});if(input.value.trim()===text)input.value='';$('#chat-error').textContent='';}catch(error){$('#chat-error').textContent=error.message;}finally{button.disabled=false;}
 };
}
function renderChat(data){
 const list=chatPanel?.querySelector('#chat-messages');if(!list)return;const messages=data.messages||[],key=messages.map(m=>m.id).join(',');if(list.dataset.key===key)return;
 const bottom=list.scrollHeight-list.scrollTop-list.clientHeight<45;list.dataset.key=key;
 list.innerHTML=messages.length?messages.map(m=>`<div class="message ${m.playerId===data.you?'mine':''}"><b>${esc(m.name)} <time>${new Date(m.at).toLocaleTimeString('ko-KR',{hour:'2-digit',minute:'2-digit'})}</time></b><p>${esc(m.text)}</p></div>`).join(''):'<p class="chat-empty">출발 전에 한마디.<br>함께 달릴 러너들에게 인사해 보세요!</p>';
 if(bottom)list.scrollTop=list.scrollHeight;
}
function updateClock(){
 if(!room||screen!=='room')return;const seconds=Math.max(0,(room.deadline-Date.now()-serverOffset)/1000),countdown=$('#countdown-number');
 if(countdown){const remain=Math.ceil(seconds),label=remain>3?'준비!':remain>0?String(remain):'GO!';if(countdown.textContent!==label)countdown.textContent=label;}
 const signal=$('#start-signal');if(signal){const elapsed=Date.now()+serverOffset-(room.deadline-room.timeLimit*1000);signal.hidden=!(room.phase==='playing'&&room.index===0&&elapsed>=0&&elapsed<2400);}
 if($('#timer')){$('#timer').textContent=`${Math.ceil(seconds)}s`;$('#time-bar').style.width=`${Math.max(0,Math.min(100,seconds/(room.phase==='reveal'?(room.explanation?8:3.5):room.timeLimit)*100))}%`;}
}
function showCreate(){
 screen='create';if(!draft.questionSource)draft.questionSource=config.aiAvailable?'ai':'bank';
 page(`<section class="page">${crumb('방 만들기')}<div class="page-title"><div><h1>어떤 주제로 달릴까요?</h1><p>좋아하는 키워드 하나로, 우리만의 레이스를 준비해요.</p></div><span class="step-tag">MAKE A RACE</span></div><form id="create-form" class="form-grid"><div><fieldset class="field plain-fieldset"><legend class="field-label">퀴즈 출제 방식</legend><div class="radio-options"><label><input type="radio" name="questionSource" value="ai" ${draft.questionSource==='ai'?'checked':''}>CLOVA AI 맞춤 출제</label><label><input type="radio" name="questionSource" value="bank" ${draft.questionSource==='bank'?'checked':''}>문제은행</label></div><p id="source-status" class="help" role="status"></p></fieldset><label class="field"><span class="field-label">퀴즈 키워드 <small>최대 60자</small></span><input class="input" name="keyword" maxlength="60" required placeholder="예: 우주, 축구, 한국사" value="${esc(draft.keyword)}"></label><div class="chips topic-chips">${topics.map(t=>`<button class="chip" type="button" data-topic="${t}" aria-pressed="${t===draft.keyword}">${t}</button>`).join('')}</div><label class="field"><span class="field-label">레이스 이름 <small>선택</small></span><input class="input" name="title" maxlength="50" placeholder="우리의 즐거운 한 바퀴" value="${esc(draft.title)}"></label><fieldset class="field plain-fieldset"><legend class="field-label">문제 수</legend>${segments('count',questionCounts,draft.count,'개')}</fieldset><fieldset class="field plain-fieldset"><legend class="field-label">문제당 제한 시간</legend>${segments('timeLimit',[5,10,15,20,30],draft.timeLimit,'초')}<p class="help">정답 1,000점 + 남은 시간에 따라 최대 500점 보너스.</p></fieldset><div class="form-pair"><label class="field"><span class="field-label">최대 인원</span><select class="input" name="capacity">${[2,4,8,20,30,50,100].map(n=>`<option value="${n}" ${n===Number(draft.capacity)?'selected':''}>${n}명</option>`).join('')}</select></label><fieldset class="field plain-fieldset"><legend class="field-label">참가 방식</legend><div class="radio-options"><label><input type="radio" name="visibility" value="public" ${draft.visibility==='public'?'checked':''}>누구나</label><label><input type="radio" name="visibility" value="friends" ${draft.visibility==='friends'?'checked':''}>초대 코드로</label></div></fieldset></div><p class="help">초대 코드로 설정하면 홈 목록에 표시되지 않아요.<br>정원이 차면 자동 출발하고, 방장은 언제든 먼저 시작할 수 있어요.</p></div><aside><div class="race-ticket"><div class="ticket-top"><b>RACE PASS</b><span>ADMIT YOUR FRIENDS</span></div>${scene('ticket','ticket-scene','새 레이스의 빨간 트랙 미리보기')}<div class="ticket-body"><h2 data-summary-room>우리의 레이스</h2><p><span data-summary-topic>주제 선택</span> · <span data-summary-visibility>누구나 참가</span></p><div class="ticket-stats"><div><strong data-summary-count>6</strong>문제</div><div><strong data-summary-time>10</strong>초 / 문제</div><div><strong data-summary-capacity>4</strong>러너</div></div><button type="button" class="ticket-profile" data-action="edit-create-profile"><span class="mini-face">${avatarMarkup(getProfile().avatar,getProfile().name)}</span><span>${esc(getProfile().name)}</span><small>수정 ↗</small></button><p id="generation-note" class="help"></p><div class="generation-progress" role="status" aria-live="polite" hidden><span class="generation-spinner" aria-hidden="true"></span><p>키워드에 맞는 퀴즈를 만들고 있어요.<br>최대 90초 정도 걸릴 수 있어요.</p></div><p class="error" role="alert"></p><button class="primary submit" type="submit">대기실 만들기 →</button></div></div></aside></form></section>`);
 const form=$('#create-form');
 function update(){
  Object.assign(draft,Object.fromEntries(new FormData(form)));const ai=draft.questionSource==='ai';
  $('#source-status').textContent=ai?(config.aiAvailable?'키워드와 문제 수에 맞춰 CLOVA Studio가 새로운 퀴즈를 만들어요.':'AI 출제를 준비 중이에요. 지금은 문제은행 모드를 이용해 주세요.'):'15개 카테고리에서 문제와 보기를 섞어 출제해요.';
  $('#generation-note').textContent=ai?'AI 문제는 정답 공개 후 해설과 함께 확인해 주세요.':'15개 카테고리 · 총 180개의 문제';form.querySelector('.submit').disabled=ai&&!config.aiAvailable;
  $('[data-summary-room]').textContent=draft.title||'우리의 레이스';$('[data-summary-topic]').textContent=draft.keyword||'주제 선택';
  for(const key of ['count','time','capacity'])$(`[data-summary-${key}]`).textContent=draft[key==='time'?'timeLimit':key];
  $('[data-summary-visibility]').textContent=draft.visibility==='friends'?'초대 코드로 참가':'누구나 참가';
  form.querySelectorAll('[data-topic]').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.topic===draft.keyword)));
 }
 form.addEventListener('input',update);form.addEventListener('change',update);update();
 form.onsubmit=async event=>{
  event.preventDefault();if(submitting)return;const body={...Object.fromEntries(new FormData(form)),...getProfile()};
  try{
   if(!body.keyword.trim())throw Error('함께 풀 퀴즈 키워드를 입력해 주세요.');setBusy(form,true);form.querySelector('.error').textContent='';
   form.querySelector('.generation-progress').hidden=body.questionSource!=='ai';form.querySelector('.submit').textContent=body.questionSource==='ai'?'AI 퀴즈 생성 중…':'출발선 준비 중…';
   const session=await api('/api/rooms',body);setBusy(form,false);enter(session);
  }catch(error){setBusy(form,false);form.querySelector('.error').textContent=error.message;form.querySelector('.generation-progress').hidden=true;form.querySelector('.submit').textContent='대기실 만들기 →';update();}
 };
}

setInterval(updateClock,100);
setInterval(()=>{if(screen==='home')loadRooms();},4000);
document.addEventListener('input',event=>{if(event.target.id==='search')renderRooms();});
document.addEventListener('change',event=>{if(event.target.id==='track-group'){trackGroup=Number(event.target.value);renderRoom();}});
document.addEventListener('click',async event=>{
 const el=event.target.closest('button');if(!el||el.disabled||submitting)return;
 try{
  if(el.dataset.filter){filter=el.dataset.filter;document.querySelectorAll('[data-filter]').forEach(b=>b.setAttribute('aria-pressed',String(b===el)));renderRooms();}
  if(el.dataset.topic){const field=$('[name=keyword]');field.value=el.dataset.topic;field.dispatchEvent(new Event('input',{bubbles:true}));}
  if(el.dataset.room)begin('join',el.dataset.room);
  if(el.dataset.answer!==undefined){
   if(answering||room.phase!=='playing'||room.yourAnswer!==null)return;
   answering=true;document.querySelectorAll('[data-answer]').forEach(b=>b.disabled=true);
   try{await api(`/api/rooms/${room.code}/answer`,{token:token(),answer:Number(el.dataset.answer)});}
   catch(error){answering=false;if(screen==='room')renderRoom();throw error;}answering=false;
  }
  switch(el.dataset.action){
   case 'create':await configReady;begin('create');break;
   case 'join':begin('join');break;
   case 'profile':returnScreen=screen;profileNext=room?'room':screen==='create'?'create':screen==='join'?'join':'home';if(screen==='join')selectedRoomCode=$('[name=code]').value;showProfile();break;
   case 'edit-create-profile':returnScreen='create';profileNext='create';showProfile();break;
   case 'edit-join-profile':selectedRoomCode=$('[name=code]').value;returnScreen='join';profileNext='join';showProfile();break;
   case 'back-profile':if(room)renderRoom();else if(returnScreen==='create')showCreate();else if(returnScreen==='join')showJoin(selectedRoomCode);else home();break;
   case 'home':history.pushState({},'','/');home();break;
   case 'new':history.pushState({},'','/');home();await configReady;begin('create');break;
   case 'refresh':await loadRooms();toast('새 레이스를 확인했어요.');break;
   case 'start':el.disabled=true;try{await api(`/api/rooms/${room.code}/start`,{token:token()});}finally{el.disabled=false;}break;
   case 'my-track':trackGroup=Math.floor(room.players.findIndex(p=>p.id===room.you)/trackSize());renderRoom();break;
   case 'theme':{
    const dark=getComputedStyle(document.documentElement).colorScheme==='dark';document.documentElement.dataset.theme=dark?'light':'dark';
    try{localStorage.setItem('quizrun-theme',dark?'light':'dark');}catch{}mountArt();break;
   }
   case 'invite':case 'copy-code':{
    const value=el.dataset.action==='invite'?`${location.origin}/?room=${room.code}`:room.code;
    try{await navigator.clipboard.writeText(value);toast('복사했어요! 친구에게 보내 주세요.');}catch{toast(`입장 코드: ${room.code}`);}break;
   }
  }
 }catch(error){toast(error.message);}
});
matchMedia('(prefers-color-scheme: dark)').addEventListener('change',()=>{if(document.documentElement.dataset.theme==='auto')mountArt();});
smallScreen.addEventListener('change',()=>{if(room&&screen==='room'){trackGroup=Math.floor(room.players.findIndex(p=>p.id===room.you)/trackSize());renderRoom();}});
window.addEventListener('popstate',()=>location.reload());
home();
const code=new URLSearchParams(location.search).get('room')?.toUpperCase();
if(code&&/^[A-Z0-9]{6}$/.test(code)){const auth=sessionStorage.getItem(`quizrun-${code}`);if(auth)connect(code,auth);else showJoin(code);}

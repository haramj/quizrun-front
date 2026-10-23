// Fixed drawing coordinates keep mouse and touch gestures consistent at any screen size.
export const cropViewSize = 512;
export const cropDiameter = 384;
const center = cropViewSize / 2;
function constrain(state) {
 const scale = cropDiameter / Math.min(state.width, state.height) * state.zoom;
 // Below 1×, allow positioning within the frame while keeping the smaller photo inside it.
 const limitX = Math.abs(state.width * scale - cropDiameter) / 2, limitY = Math.abs(state.height * scale - cropDiameter) / 2;
 return {...state, x: Math.max(center - limitX, Math.min(center + limitX, state.x)), y: Math.max(center - limitY, Math.min(center + limitY, state.y))};
}
export function createCrop(width, height) { return {width, height, x: center, y: center, zoom: 1}; }
export function moveCrop(state, dx, dy) { return constrain({...state, x: state.x + dx, y: state.y + dy}); }
export function zoomCrop(state, zoom, anchor = {x: center, y: center}) {
 const nextZoom = Math.max(.5, Math.min(5, zoom)), ratio = nextZoom / state.zoom;
 return constrain({...state, zoom: nextZoom, x: anchor.x + (state.x - anchor.x) * ratio, y: anchor.y + (state.y - anchor.y) * ratio});
}
export function sourceRect(state) {
 const scale = cropDiameter / Math.min(state.width, state.height) * state.zoom;
 return {x: (center - cropDiameter / 2 - state.x) / scale + state.width / 2, y: (center - cropDiameter / 2 - state.y) / scale + state.height / 2, size: cropDiameter / scale};
}
const esc = s => String(s).replace(/[&<>"']/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));
let clipId = 0;
let memoryProfile;
export function getProfile() {
 if (memoryProfile) return memoryProfile;
 try { return {name: localStorage.getItem('quizrun-name') || '', avatar: localStorage.getItem('quizrun-avatar') || ''}; }
 catch { return {name: '', avatar: ''}; }
}
export function saveProfile(profile) {
 memoryProfile = profile;
 try { localStorage.setItem('quizrun-name', profile.name); localStorage.setItem('quizrun-avatar', profile.avatar); return true; }
 catch { return false; }
}
export function avatarMarkup(avatar, name = '') {
 return avatar ? `<img src="${esc(avatar)}" alt="${esc(name ? name + '의 프로필' : '프로필 사진')}" class="profile-photo">` : '<span aria-hidden="true">☺</span>';
}
export function runner(color = '#d9ff43', cls = '', avatar = '') {
 const id = `face-${++clipId}`;
 return `<svg class="runner ${cls}${avatar ? ' photo-runner' : ''}" viewBox="0 0 100 120" fill="none" aria-hidden="true"><path d="M24 99 43 77 56 83 72 111" stroke="#20231e" stroke-width="10" stroke-linecap="round"/><path d="m51 63 19 10 15-13M46 59 29 68 17 59" stroke="#20231e" stroke-width="8" stroke-linecap="round"/><path d="m42 53 23 7-11 29-24-8z" fill="${color}" stroke="#20231e" stroke-width="3"/><circle cx="57" cy="30" r="27" fill="#f6f4e9" stroke="#20231e" stroke-width="3"/>${avatar ? `<defs><clipPath id="${id}"><circle cx="57" cy="30" r="25"/></clipPath></defs><image href="${esc(avatar)}" x="32" y="5" width="50" height="50" preserveAspectRatio="xMidYMid slice" clip-path="url(#${id})"/><circle cx="57" cy="30" r="27" stroke="${color}" stroke-width="3"/>` : `<path d="M32 24c0-26 48-26 50 0H32Z" fill="${color}" stroke="#20231e" stroke-width="3"/><path d="M45 31h8m12 0h8" stroke="#20231e" stroke-width="5"/><path d="m53 42 10 1" stroke="#20231e" stroke-width="2"/>`}<path d="m12 109 13-7m45 12 13 1" stroke="#20231e" stroke-width="7" stroke-linecap="round"/></svg>`;
}

export function profileEditorMarkup() {
 return `<fieldset class="profile-editor"><legend>나만의 러너 얼굴</legend><div class="profile-editor-top"><div class="crop-preview" id="crop-preview"><canvas id="avatar-canvas" width="256" height="256" aria-label="프로필 사진 원형 자르기 미리보기" hidden></canvas><span id="avatar-placeholder" aria-hidden="true">☺</span></div><div class="photo-actions"><label class="photo-upload">사진 선택<input id="avatar-file" type="file" accept="image/jpeg,image/png,image/webp" aria-label="프로필 사진 선택"></label><div class="photo-edit-actions"><button type="button" class="photo-remove" id="avatar-edit">사진 자르기</button><button type="button" class="photo-remove" id="avatar-remove">기본 얼굴로</button></div><small>JPG · PNG · WebP, 최대 8MB</small></div><div class="runner-preview" id="runner-preview"></div></div><p class="photo-hint" id="avatar-hint" role="status">사진을 골라 원하는 부분을 원 안에 맞춰 주세요.</p><div id="avatar-cropper" class="avatar-cropper" role="dialog" aria-modal="true" aria-labelledby="crop-title" aria-describedby="crop-instructions" hidden><section class="avatar-crop-card"><div class="crop-heading"><div><span class="eyebrow">MAKE IT YOURS</span><h3 id="crop-title">사진 자르기</h3></div><button type="button" class="crop-close" data-crop-cancel aria-label="사진 자르기 취소">×</button></div><p id="crop-instructions">사진을 움직여 원 안에 얼굴을 맞춰 주세요.<br>두 손가락이나 아래 막대로 0.5~5배 조절할 수 있어요.</p><div class="crop-stage"><canvas id="crop-canvas" width="512" height="512" tabindex="0" aria-label="자를 사진. 드래그 또는 방향키로 이동, 더하기와 빼기로 확대와 축소"></canvas><span class="crop-drag-hint" aria-hidden="true">↔ 사진을 움직여 보세요</span></div><div class="crop-zoom-row"><label for="crop-zoom">크기</label><input id="crop-zoom" type="range" min="0.5" max="5" step="0.01" value="1"><output id="crop-zoom-value" for="crop-zoom">1.0×</output></div><div class="crop-reset-row"><span>원 안의 부분만 사용해요. 축소 여백은 흰색이에요.</span><button type="button" id="crop-reset">가운데 맞춤 ↺</button></div><div class="crop-footer"><button type="button" class="btn outline" data-crop-cancel>취소</button><button type="button" class="btn dark" id="crop-apply">이대로 사용 ✓</button></div></section></div></fieldset>`;
}

export function mountProfileEditor(form, {onChange = () => {}} = {}) {
 let avatar = getProfile().avatar, bitmap, draft, state, appliedState, version = 0, busy = false, disposed = false, frame;
 const canvas = form.querySelector('#avatar-canvas'), context = canvas.getContext('2d');
 const input = form.querySelector('#avatar-file'), edit = form.querySelector('#avatar-edit');
 const cropper = form.querySelector('#avatar-cropper'), stage = form.querySelector('#crop-canvas'), stageContext = stage.getContext('2d');
 const zoom = form.querySelector('#crop-zoom'), zoomValue = form.querySelector('#crop-zoom-value');
 const hint = form.querySelector('#avatar-hint');
 const pointers = new Map();
 let returnFocus;
 function preview() {
  form.querySelector('#runner-preview').innerHTML = runner('#d9ff43', '', avatar);
  form.querySelector('#avatar-placeholder').hidden = Boolean(avatar);
  canvas.hidden = !avatar;
  edit.disabled = !avatar || busy;
  onChange(avatar);
 }
 function drawStage() {
  frame = undefined;
  if (!draft || cropper.hidden || disposed) return;
  const scale = cropDiameter / Math.min(draft.width, draft.height) * state.zoom;
  const width = draft.width * scale, height = draft.height * scale, center = cropViewSize / 2;
  stageContext.fillStyle = '#fff'; stageContext.fillRect(0, 0, cropViewSize, cropViewSize);
  stageContext.drawImage(draft, state.x - width / 2, state.y - height / 2, width, height);
  stageContext.fillStyle = '#090615b8'; stageContext.beginPath();
  stageContext.rect(0, 0, cropViewSize, cropViewSize);
  stageContext.arc(center, center, cropDiameter / 2, 0, Math.PI * 2, true);
  stageContext.fill('evenodd');
  stageContext.beginPath(); stageContext.arc(center, center, cropDiameter / 2, 0, Math.PI * 2);
  stageContext.strokeStyle = '#fff3da'; stageContext.lineWidth = 3; stageContext.stroke();
  zoom.value = String(state.zoom); zoomValue.textContent = `${state.zoom.toFixed(1)}×`;
 }
 function drawSoon() { if (frame === undefined) frame = requestAnimationFrame(drawStage); }
 function openCrop(next, initial) {
  returnFocus = document.activeElement;
  draft = next; state = initial ? {...initial} : createCrop(next.width, next.height);
  pointers.clear(); cropper.hidden = false; drawStage(); stage.focus({preventScroll: true});
 }
 function closeCrop() {
  if (draft && draft !== bitmap) draft.close();
  draft = undefined; pointers.clear(); cropper.hidden = true; stage.classList.remove('is-dragging');
  if (returnFocus?.isConnected) returnFocus.focus({preventScroll: true});
 }
 function applyCrop() {
  if (!draft) return;
  const rect = sourceRect(state);
  context.fillStyle = '#fff'; context.fillRect(0, 0, 256, 256);
  context.drawImage(draft, rect.x, rect.y, rect.size, rect.size, 0, 0, 256, 256);
  avatar = canvas.toDataURL('image/jpeg', 0.8);
  if (avatar.length > 65000) avatar = canvas.toDataURL('image/jpeg', 0.55);
  if (bitmap && bitmap !== draft) bitmap.close();
  bitmap = draft; appliedState = {...state}; closeCrop(); preview();
  hint.textContent = '원을 맞춰 잘랐어요! 내 러너 얼굴에서 확인해 보세요.';
 }
 async function readSavedPhoto(value) {
  const image = new Image(); image.src = value;
  await image.decode(); return createImageBitmap(image);
 }
 if (avatar) {
  const initial = avatar, image = new Image();
  image.onload = () => { if (!disposed && avatar === initial) context.drawImage(image, 0, 0, 256, 256); }; image.src = initial;
 }
 preview();
 input.onchange = async () => {
  const file = input.files[0]; if (!file) return;
  const request = ++version; busy = true; preview();
  try {
   if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type) || file.size > 8 * 1024 * 1024) throw Error('8MB 이하의 JPG, PNG, WebP 사진을 골라 주세요.');
   hint.textContent = '사진을 준비하고 있어요…';
   const next = await createImageBitmap(file);
   if (request !== version || disposed || !form.isConnected) { next.close(); return; }
   if (next.width * next.height > 40000000) { next.close(); throw Error('사진 해상도가 너무 커요. 조금 작은 사진을 골라 주세요.'); }
   openCrop(next); hint.textContent = '사진의 원하는 부분을 원에 맞춰 자를 수 있어요.';
  } catch (error) { if (request === version) hint.textContent = error.message || '사진을 읽을 수 없어요. 다른 사진을 선택해 주세요.'; }
  finally { if (request === version) { busy = false; preview(); } input.value = ''; }
 };
 edit.onclick = async () => {
  if (busy || !avatar) return;
  if (bitmap) { openCrop(bitmap, appliedState); return; }
  const request = ++version; busy = true; preview();
  try {
   const next = await readSavedPhoto(avatar);
   if (request !== version || disposed || !form.isConnected) { next.close(); return; }
   bitmap = next; appliedState = createCrop(next.width, next.height); openCrop(bitmap, appliedState);
  } catch { if (request === version) hint.textContent = '저장된 사진을 읽을 수 없어요. 사진을 다시 골라 주세요.'; }
  finally { if (request === version) { busy = false; preview(); } }
 };
 function point(event) {
  const bounds = stage.getBoundingClientRect();
  return {x: (event.clientX - bounds.left) * cropViewSize / bounds.width, y: (event.clientY - bounds.top) * cropViewSize / bounds.height};
 }
 stage.onpointerdown = event => {
  if (!draft || (event.pointerType === 'mouse' && event.button !== 0)) return;
  event.preventDefault(); pointers.set(event.pointerId, point(event)); stage.setPointerCapture(event.pointerId);
  stage.focus({preventScroll: true}); stage.classList.add('is-dragging');
 };
 stage.onpointermove = event => {
  if (!draft || !pointers.has(event.pointerId)) return;
  const before = [...pointers.values()]; pointers.set(event.pointerId, point(event)); const after = [...pointers.values()];
  if (after.length === 1) state = moveCrop(state, after[0].x - before[0].x, after[0].y - before[0].y);
  else if (after.length === 2) {
   const midpoint = points => ({x: (points[0].x + points[1].x) / 2, y: (points[0].y + points[1].y) / 2});
   const distance = points => Math.hypot(points[1].x - points[0].x, points[1].y - points[0].y);
   const oldMid = midpoint(before), newMid = midpoint(after), oldDistance = distance(before);
   if (oldDistance > 0) state = zoomCrop(state, state.zoom * distance(after) / oldDistance, oldMid);
   state = moveCrop(state, newMid.x - oldMid.x, newMid.y - oldMid.y);
  }
  drawSoon();
 };
 function release(event) { pointers.delete(event.pointerId); if (!pointers.size) stage.classList.remove('is-dragging'); }
 stage.onpointerup = stage.onpointercancel = stage.onlostpointercapture = release;
 function wheel(event) {
  if (!draft) return;
  event.preventDefault(); state = zoomCrop(state, state.zoom * Math.exp(-Math.max(-100, Math.min(100, event.deltaY)) * .002), point(event)); drawSoon();
 }
 stage.addEventListener('wheel', wheel, {passive: false});
 zoom.oninput = () => { if (draft) { state = zoomCrop(state, Number(zoom.value)); drawSoon(); } };
 form.querySelector('#crop-reset').onclick = () => { if (draft) { state = createCrop(draft.width, draft.height); drawSoon(); } };
 form.querySelector('#crop-apply').onclick = applyCrop;
 cropper.querySelectorAll('[data-crop-cancel]').forEach(button => button.onclick = closeCrop);
 function keyboard(event) {
  if (cropper.hidden) return;
  if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); closeCrop(); return; }
  if (event.key === 'Tab') {
   const focusable = [...cropper.querySelectorAll('button,input,[tabindex]')].filter(el => !el.disabled);
   const index = focusable.indexOf(document.activeElement), next = event.shiftKey ? index - 1 : index + 1;
   if (next < 0 || next >= focusable.length) { event.preventDefault(); focusable[(next + focusable.length) % focusable.length].focus(); }
  }
  if (event.target !== stage) return;
  const steps = {ArrowLeft: [-10, 0], ArrowRight: [10, 0], ArrowUp: [0, -10], ArrowDown: [0, 10]};
  if (steps[event.key]) { event.preventDefault(); state = moveCrop(state, ...steps[event.key]); drawSoon(); }
  else if (['+', '=', '-', '_'].includes(event.key)) { event.preventDefault(); state = zoomCrop(state, state.zoom + (['+', '='].includes(event.key) ? .1 : -.1)); drawSoon(); }
  else if (event.key === 'Enter') { event.preventDefault(); applyCrop(); }
 };
 form.addEventListener('keydown', keyboard, true);
 form.querySelector('#avatar-remove').onclick = () => {
  version++; busy = false; closeCrop(); bitmap?.close(); bitmap = undefined; avatar = ''; appliedState = undefined;
  hint.textContent = '기본 얼굴로 달릴게요. 언제든 사진을 다시 고를 수 있어요.'; preview();
 };
 return {
  getAvatar() {
   if (busy) throw Error('사진 준비가 끝나면 다시 눌러 주세요.');
   if (!cropper.hidden) throw Error('사진 자르기를 적용하거나 취소한 뒤 저장해 주세요.');
   return avatar;
  },
  dispose() { disposed = true; version++; cancelAnimationFrame(frame); if (draft && draft !== bitmap) draft.close(); bitmap?.close(); form.removeEventListener('keydown', keyboard, true); stage.removeEventListener('wheel', wheel); }
 };
}

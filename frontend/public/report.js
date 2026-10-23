import {request} from './quiz-state.js';

/** Freeze the selected room/question so polling cannot turn a report into a different one. */
export function showReportDialog({room,token,onStatus=()=>{}}){
 const code=room.code,index=Math.min(room.index,room.total-1),returnFocus=document.activeElement;
 const dialog=document.createElement('dialog');dialog.className='report-dialog';dialog.setAttribute('aria-labelledby','report-title');
 dialog.innerHTML=`<form><div class="report-heading"><h2 id="report-title">문제 신고</h2><button type="button" data-dismiss aria-label="신고 창 닫기">×</button></div><p class="report-question"></p><label class="field report-index"><span class="field-label">신고할 문제</span><select class="input" name="index" aria-label="신고할 문제"></select></label><label class="field"><span class="field-label">신고 사유</span><select class="input" name="reason"><option value="WRONG_ANSWER">정답 오류</option><option value="AMBIGUOUS">모호한 문제나 보기</option><option value="OFFENSIVE">부적절한 내용</option><option value="OTHER">기타</option></select></label><label class="field"><span class="field-label">자세한 내용 <small>선택 · 최대 200자</small></span><textarea class="input" name="detail" maxlength="200" rows="3" placeholder="어떤 점을 확인하면 좋을까요?"></textarea></label><p class="help">신고 내용은 다른 참가자에게 보이지 않아요.<br>신고해도 진행 중인 문제와 점수는 바뀌지 않아요.</p><p class="error" role="alert"></p><div class="report-actions"><button class="secondary" type="button" data-dismiss>취소</button><button class="primary" type="submit">신고 보내기</button></div></form>`;
 const form=dialog.querySelector('form'),indexField=form.elements.index;
 dialog.querySelector('.report-question').textContent=room.phase==='finished'?'레이스에서 나온 문제를 선택해 주세요.':`문제 ${index+1} · ${room.question?.text||''}`;
 if(room.phase==='finished'){
  for(let i=0;i<room.total;i++)indexField.add(new Option(`문제 ${i+1}`,String(i),false,i===index));
 }else{indexField.add(new Option(`문제 ${index+1}`,String(index),true,true));dialog.querySelector('.report-index').hidden=true;}
 dialog.querySelectorAll('[data-dismiss]').forEach(button=>button.onclick=()=>dialog.close());
 dialog.addEventListener('close',()=>{dialog.remove();if(returnFocus?.isConnected)returnFocus.focus({preventScroll:true});},{once:true});
 form.onsubmit=async event=>{
  event.preventDefault();const submit=form.querySelector('[type=submit]');if(submit.disabled)return;
  const body={token,index:Number(indexField.value),reason:form.elements.reason.value,detail:form.elements.detail.value.trim()};
  submit.disabled=true;form.setAttribute('aria-busy','true');form.querySelector('.error').textContent='';
  try{
   const response=await request(`/api/rooms/${code}/report`,body);
   if(!dialog.isConnected)return;dialog.close();onStatus(response.recorded?'문제 신고를 접수했어요. 확인에 도움을 주셔서 감사합니다.':'이미 신고했거나 문제은행과 연결되지 않은 문제예요.');
  }catch(error){if(dialog.isConnected)form.querySelector('.error').textContent=error.message;}
  finally{submit.disabled=false;form.removeAttribute('aria-busy');}
 };
 document.body.append(dialog);dialog.showModal();return dialog;
}

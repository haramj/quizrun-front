import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createRoomPayload,preparation,sourceLabel} from './public/quiz-state.js';

test('Spring room payload uses numbers, a 30-character keyword and no obsolete source selector',()=>{
 const payload=createRoomPayload({keyword:'  손흥민  ',title:'  우리 레이스 ',count:'12',timeLimit:'5',capacity:'100',visibility:'friends',questionSource:'ai'},{name:'러너',avatar:'photo'});
 assert.deepEqual(payload,{keyword:'손흥민',title:'우리 레이스',name:'러너',avatar:'photo',count:12,timeLimit:5,capacity:100,visibility:'friends'});
 for(const keyword of ['', 'a'.repeat(31)])assert.throws(()=>createRoomPayload({...payload,keyword},{name:'러너',avatar:''}));
 for(const change of [{count:5},{timeLimit:2},{capacity:2.5}])assert.throws(()=>createRoomPayload({...payload,...change},{name:'러너',avatar:''}));
});

test('preparing permits a queued start, a failed preparation stops start, and fewer questions are explained',()=>{
 const waiting={prepare:'preparing',total:12,timeLimit:10,source:null};
 assert.equal(preparation(waiting).canStart,true);
 assert.equal(preparation({...waiting,startRequested:true}).canStart,false);
 assert.match(preparation({...waiting,startRequested:true}).detail,/자동/);
 const failed=preparation({...waiting,prepare:'failed',prepareError:'참고할 자료가 없어요.'});
 assert.equal(failed.canStart,false);assert.equal(failed.detail,'참고할 자료가 없어요.');
 const ready=preparation({...waiting,prepare:'ready',requestedCount:12,total:7,source:'mixed'});
 assert.equal(ready.canStart,true);assert.match(ready.detail,/12문제.*7문제/);
 assert.equal(sourceLabel('mixed'),'문제은행 + AI 출제');
});

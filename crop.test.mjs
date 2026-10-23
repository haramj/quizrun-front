import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createCrop, moveCrop, zoomCrop, sourceRect, cropDiameter} from './public/profile.js';

function contained(state) {
 const rect = sourceRect(state), epsilon = 1e-8;
 assert.ok(rect.x >= -epsilon && rect.y >= -epsilon);
 assert.ok(rect.x + rect.size <= state.width + epsilon && rect.y + rect.size <= state.height + epsilon);
}

test('portrait, landscape and square images start centered with the full crop covered',()=>{
 for (const [width,height] of [[1600,900],[900,1600],[256,256]]) {
  const rect = sourceRect(createCrop(width,height));
  assert.ok(Math.abs(rect.size-Math.min(width,height))<1e-8);
  assert.ok(Math.abs(rect.x-(width-rect.size)/2)<1e-8);assert.ok(Math.abs(rect.y-(height-rect.size)/2)<1e-8);
 }
});

test('dragging follows the finger while image edges never reveal blank crop space',()=>{
 for (const [width,height] of [[1600,900],[900,1600],[256,256]]) {
  let state=zoomCrop(createCrop(width,height),2);
  const before=sourceRect(state);state=moveCrop(state,30,-20);const after=sourceRect(state);
  assert.ok(after.x<before.x);assert.ok(after.y>before.y);
  for (const [dx,dy] of [[10000,10000],[-20000,-20000],[0,20000],[20000,0]]) {state=moveCrop(state,dx,dy);contained(state);}
 }
});

test('zoom keeps the photo beneath the gesture midpoint and clamps magnification',()=>{
 let state=zoomCrop(createCrop(1200,1200),2);
 const anchor={x:300,y:280};
 const pixelAt=(value,point)=>({x:(point.x-value.x)/(cropDiameter/Math.min(value.width,value.height)*value.zoom)+value.width/2,y:(point.y-value.y)/(cropDiameter/Math.min(value.width,value.height)*value.zoom)+value.height/2});
 const before=pixelAt(state,anchor);state=zoomCrop(state,3,anchor);const after=pixelAt(state,anchor);
 assert.ok(Math.abs(before.x-after.x)<1e-8);assert.ok(Math.abs(before.y-after.y)<1e-8);
 state=zoomCrop(state,100);assert.equal(state.zoom,5);contained(state);
 state=zoomCrop(moveCrop(state,1000,-1000),0);assert.equal(state.zoom,.5);
 const rect=sourceRect(state);assert.ok(rect.x<=0&&rect.y<=0);assert.ok(rect.x+rect.size>=1200&&rect.y+rect.size>=1200);
 const centered=zoomCrop(createCrop(1200,1200),.5);
 assert.deepEqual(sourceRect(centered),{x:-600,y:-600,size:2400});
 const moved=sourceRect(moveCrop(centered,10000,-10000));
 assert.ok(moved.x<=0&&moved.y<=0);assert.ok(moved.x+moved.size>=1200&&moved.y+moved.size>=1200);
});

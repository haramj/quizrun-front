import {createHash} from 'node:crypto';

const maxBytes = 48 * 1024;
// Only browser-normalized JPEGs are accepted; SVGs and external image URLs never enter the room.
export function parseAvatar(value) {
 if (value === undefined || value === '') return null;
 if (typeof value !== 'string' || value.length > 66000 || !/^data:image\/jpeg;base64,[A-Za-z0-9+/]+={0,2}$/.test(value)) {
  throw Error('프로필 사진을 다시 선택해 주세요. JPG 형식의 작은 사진만 사용할 수 있어요.');
 }
 const bytes = Buffer.from(value.slice(value.indexOf(',') + 1), 'base64');
 if (bytes.length > maxBytes || bytes[0] !== 0xff || bytes[1] !== 0xd8 || bytes.at(-2) !== 0xff || bytes.at(-1) !== 0xd9) {
  throw Error('프로필 사진 형식이나 크기가 올바르지 않아요. 사진을 다시 선택해 주세요.');
 }
 let dimensions;
 for (let offset = 2; offset + 4 < bytes.length;) {
  if (bytes[offset] !== 0xff) break;
  const marker = bytes[offset + 1];
  if (marker === 0xda || marker === 0xd9) break;
  const length = bytes.readUInt16BE(offset + 2);
  if (length < 2 || offset + 2 + length > bytes.length) break;
  if ([0xc0, 0xc1, 0xc2].includes(marker) && length >= 8) {
   dimensions = {height: bytes.readUInt16BE(offset + 5), width: bytes.readUInt16BE(offset + 7)};
   break;
  }
  offset += 2 + length;
 }
 if (!dimensions || dimensions.width < 1 || dimensions.height < 1 || dimensions.width > 256 || dimensions.height > 256) {
  throw Error('프로필 사진은 256px 이하로 저장해 주세요. 사진을 다시 선택하면 자동 조절돼요.');
 }
 return {id: createHash('sha256').update(bytes).digest('hex'), bytes};
}

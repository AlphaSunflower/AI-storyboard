import type { SceneMaterialReferenceInput } from '../api/assets';
import { assetUrl } from '../config';

function blobToDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error('读取项目库素材失败'));
    reader.onloadend = () => {
      if (typeof reader.result === 'string') resolve(reader.result);
      else reject(new Error('读取项目库素材失败'));
    };
    reader.readAsDataURL(blob);
  });
}

export async function projectLibraryReferencesToDataUrls(
  references: SceneMaterialReferenceInput[],
): Promise<string[]> {
  return Promise.all(references.map(async (reference) => {
    const response = await fetch(assetUrl(reference.url));
    if (!response.ok) {
      throw new Error(`读取项目库素材失败：${reference.fileName ?? reference.url}`);
    }
    const blob = await response.blob();
    if (!blob.type.startsWith('image/')) {
      throw new Error(`项目库素材不是图片：${reference.fileName ?? reference.url}`);
    }
    return blobToDataUrl(blob);
  }));
}

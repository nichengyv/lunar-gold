import { getPublicKey } from '../api/auth';
import { JSEncrypt } from 'jsencrypt';

/** 向后端取公钥并用 RSA 加密明文密码，返回 Base64 密文 */
export async function encryptPassword(password: string): Promise<string> {
  const publicKey = await getPublicKey();
  if (!publicKey) throw new Error('获取公钥失败');
  const encryptor = new JSEncrypt();
  encryptor.setPublicKey(publicKey);
  const encrypted = encryptor.encrypt(password);
  if (!encrypted) throw new Error('密码加密失败');
  return encrypted;
}

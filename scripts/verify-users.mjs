// Real API checks. Creates two isolated development users; never prints credentials.
import assert from 'node:assert/strict';
import {randomBytes} from 'node:crypto';
import {readFileSync,writeFileSync,mkdirSync,existsSync} from 'node:fs';
const root=new URL('../',import.meta.url),base='http://127.0.0.1:8080/api';
const stamp=Date.now(),label='Directory verification '+stamp;
const credentials=[0,1].map(n=>({email:`directory-${stamp}-${n}@example.test`,password:randomBytes(20).toString('hex'),fullName:label+(n?' Second':' % First')}));
mkdirSync(new URL('.local/',root),{recursive:true});
writeFileSync(new URL(`.local/users-verification-${stamp}.json`,root),JSON.stringify(credentials,null,2));
async function request(path,token,body,status=200){const response=await fetch(base+path,{method:body?'POST':'GET',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},body:body?JSON.stringify(body):undefined});assert.equal(response.status,status,path+' HTTP status');return response.json();}
const dev=Object.fromEntries(readFileSync(new URL(existsSync(new URL('.local/runtime-dev.env',root))?'.local/runtime-dev.env':'.env.development',root),'utf8').replace(/^\uFEFF/,'').split(/\r?\n/).filter(l=>l.includes('=')&&!l.trim().startsWith('#')).map(l=>{const i=l.indexOf('=');return[l.slice(0,i).trim(),l.slice(i+1).trim().replace(/^['"]|['"]$/g,'')];}));
const first=await request('/auth/register',null,credentials[0]);
await request('/auth/register',null,credentials[1]);
const admin=await request('/auth/login',null,{email:dev.DEV_ADMIN_EMAIL,password:dev.DEV_ADMIN_PASSWORD});
await request('/admin/users',null,undefined,401);
await request('/admin/users',first.token,undefined,403);
console.log('PASS anonymous and CUSTOMER access denied; ADMIN authenticated against native MySQL');
const query='/admin/users?search='+encodeURIComponent('  '+label.toUpperCase()+'  ')+'&size=1';
const page0=await request(query+'&page=0',admin.token),page1=await request(query+'&page=1',admin.token);
assert.equal(page0.totalElements,2);assert.equal(page0.totalPages,2);assert.equal(page1.content.length,1);assert.notEqual(page0.content[0].id,page1.content[0].id);
for(const row of [...page0.content,...page1.content]){assert.deepEqual(Object.keys(row).sort(),['createdAt','email','fullName','id','role']);assert.ok(credentials.some(c=>c.email===row.email&&c.fullName===row.fullName));assert.equal(row.role,'CUSTOMER');assert.ok(Number.isFinite(Date.parse(row.createdAt)));}
assert.deepEqual((await request(query+'&page=0',admin.token)).content,page0.content);
console.log('PASS real names/emails/dates, trimmed case-insensitive search, stable pagination and exactly five allowlisted fields');
const email=await request('/admin/users?search='+encodeURIComponent(credentials[0].email),admin.token);assert.equal(email.totalElements,1);
const id=email.content[0].id;assert.ok((await request('/admin/users?search='+id,admin.token)).content.some(row=>row.id===id));
const role=await request('/admin/users?search=admin',admin.token);assert.ok(role.content.some(row=>row.role==='ADMIN'));
const literal=await request('/admin/users?search='+encodeURIComponent('%'),admin.token);assert.ok(literal.content.some(row=>row.id===id));assert.ok(literal.content.every(row=>row.fullName.includes('%')||row.email.includes('%')));
assert.equal((await request('/admin/users?search='+encodeURIComponent("' OR 1=1 --"),admin.token)).totalElements,0);
assert.equal((await request(query+'&page=99',admin.token)).content.length,0);
for(const suffix of ['page=-1','size=0','size=101','search='+'x'.repeat(256)])await request('/admin/users?'+suffix,admin.token,undefined,400);
console.log('PASS email/ID/role search, literal wildcard handling, empty pages, and invalid search/page validation');

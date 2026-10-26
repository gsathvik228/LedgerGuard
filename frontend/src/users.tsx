import { useState, type FormEvent } from 'react';
import type { Page } from './types';
import { Badge, Empty, ErrorBox, Loading, timestamp, useResource } from './ui';

type RegisteredUser = { id:number; fullName:string; email:string; role:string; createdAt:string };

export function UsersPage() {
  const [input,setInput]=useState('');
  const [search,setSearch]=useState('');
  const [page,setPage]=useState(0);
  const [size,setSize]=useState(20);
  const query=new URLSearchParams({search,page:String(page),size:String(size)});
  const users=useResource<Page<RegisteredUser>>('/admin/users?'+query);
  function submit(event:FormEvent<HTMLFormElement>) {
    event.preventDefault();setSearch(input.trim());setPage(0);
  }
  return <>
    <div className="page-heading"><div><p className="eyebrow">ADMINISTRATION</p><h1>Registered users</h1></div></div>
    <section className="panel">
      <p className="muted">Search by name, email, exact user ID, or role. Most recently registered users appear first.</p>
      <form className="filters" onSubmit={submit}>
        <label>Search users<input type="search" value={input} onChange={e=>setInput(e.target.value)} maxLength={255} placeholder="Name, email, ID or role"/></label>
        <label>Users per page<select value={size} onChange={e=>{setSize(Number(e.target.value));setPage(0);}}>{[10,20,50,100].map(value=><option key={value} value={value}>{value}</option>)}</select></label>
        <button>Search</button>
        <button type="button" className="secondary" onClick={()=>{setInput('');setSearch('');setPage(0);}}>Clear</button>
      </form>
      {users.loading?<Loading/>:users.error?<ErrorBox message={users.error} retry={users.retry}/>:<>
        {!users.data?.content.length?<Empty>{search?'No registered users match your search.':'No registered users on this page.'}</Empty>:
          <div className="table-wrap"><table><thead><tr><th scope="col">ID</th><th scope="col">Name</th><th scope="col">Email</th><th scope="col">Role</th><th scope="col">Registered</th></tr></thead>
            <tbody>{users.data.content.map(user=><tr key={user.id}><td>#{user.id}</td><td>{user.fullName}</td><td>{user.email}</td><td><Badge value={user.role}/></td><td><time dateTime={user.createdAt}>{timestamp(user.createdAt)}</time></td></tr>)}</tbody>
          </table></div>}
        <div className="pagination"><span>{users.data?.totalElements||0} users · Page {page+1} of {Math.max(1,users.data?.totalPages||0)}</span><div>
          <button className="secondary" disabled={page===0} onClick={()=>setPage(p=>p-1)}>Previous</button>
          <button className="secondary" disabled={page+1>=(users.data?.totalPages||0)} onClick={()=>setPage(p=>p+1)}>Next</button>
        </div></div>
      </>}
    </section>
  </>;
}

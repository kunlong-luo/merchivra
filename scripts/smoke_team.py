#!/usr/bin/env python3
"""V15 real HTTP+PG multi-Owner invite/activation/reviewer/revocation smoke."""
import http.cookiejar
import json
import os
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import HTTPCookieProcessor,Request,build_opener

BASE="http://127.0.0.1:3000"
def session():
    return build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
owner=session()
member=session()
def hit(op,path,method="GET",csrf=None,body=None):
    headers={"Accept":"application/json"}
    if csrf:headers["X-CSRF-TOKEN"]=csrf
    data=None if body is None else json.dumps(body).encode()
    if data is not None:headers["Content-Type"]="application/json"
    try:
        with op.open(Request(BASE+path,method=method,headers=headers,data=data),timeout=25) as r:
            code,payload=r.status,r.read()
    except HTTPError as e:
        code,payload=e.code,e.read()
    try:return code,json.loads(payload)
    except (ValueError,UnicodeError):return code,payload.decode(errors="replace")
def expect(op,path,status=200,**kw):
    code,payload=hit(op,path,**kw)
    assert code==status,(path,status,code,str(payload)[:200])
    return payload
def csrf(op):return expect(op,"/api/v1/auth/csrf")["token"]
def login(op,user,password):
    token=csrf(op)
    data=urlencode({"username":user,"password":password}).encode()
    try:
        with op.open(Request(BASE+"/api/v1/auth/login",data=data,method="POST",
            headers={"Content-Type":"application/x-www-form-urlencoded",
                     "X-CSRF-TOKEN":token}),timeout=25) as response:
            assert response.status==200
    except HTTPError as e:raise AssertionError("Login HTTP "+str(e.code)) from e

expect(owner,"/api/v1/team/members",401)
code,_=hit(owner,"/api/v1/auth/invitations/accept",method="POST",body={})
assert code in (401,403),("anonymous unprotected activation",code)
login(owner,os.environ["MERCHIVRA_BOOTSTRAP_USERNAME"],os.environ["MERCHIVRA_BOOTSTRAP_PASSWORD"])
oc=csrf(owner)
self_user=expect(owner,"/api/v1/auth/me")["userId"]
members=expect(owner,"/api/v1/team/members")
assert len(members)==1 and members[0]["userId"]==self_user
expect(owner,"/api/v1/team/invitations",200)
expect(owner,"/api/v1/team/invitations",403,method="POST",body={"loginName":"reviewer15"})
created=expect(owner,"/api/v1/team/invitations",method="POST",csrf=oc,
  body={"loginName":"reviewer15"})
token=created["token"]
assert len(token)==43 and created["loginName"]=="reviewer15"
assert "token" not in json.dumps(expect(owner,"/api/v1/team/invitations"))
expect(owner,"/api/v1/team/invitations",409,method="POST",csrf=oc,
  body={"loginName":"reviewer15"})
mc=csrf(member)
code,_=hit(member,"/api/v1/auth/invitations/accept",method="POST",csrf=mc,
  body={"token":"A"*43,"password":"test-another-strong-secret-1234"})
assert code in (400,401),("invalid anonymous invitation must not activate",code)
expect(member,"/api/v1/auth/invitations/accept",204,method="POST",csrf=mc,
  body={"token":token,"password":"test-another-strong-secret-1234"})
code,_=hit(member,"/api/v1/auth/invitations/accept",method="POST",csrf=mc,
  body={"token":token,"password":"test-another-strong-secret-1234"})
assert code in (400,401),("replayed invitation must not activate",code)
login(member,"reviewer15","test-another-strong-secret-1234")
bc=csrf(member)
second=expect(member,"/api/v1/auth/me")["userId"]
assert second!=self_user
assert len(expect(owner,"/api/v1/team/members"))==2
expect(member,"/api/v1/team/members/"+second+"/reviewer",403,method="POST",csrf=bc,
  body={"enabled":True})
expect(owner,"/api/v1/team/members/"+self_user+"/reviewer",403,method="POST",csrf=oc,
  body={"enabled":True})
expect(owner,"/api/v1/team/members/"+second+"/reviewer",204,method="POST",csrf=oc,
  body={"enabled":True})
rows=expect(owner,"/api/v1/team/members")
assert any(m["userId"]==second and m["reviewer"] for m in rows)
expect(owner,"/api/v1/team/members/"+second+"/reviewer",204,method="POST",csrf=oc,
  body={"enabled":False})
assert not any(m["userId"]==second and m["reviewer"] for m in expect(owner,"/api/v1/team/members"))
expect(owner,"/api/v1/team/members/"+self_user+"/revoke",403,method="POST",csrf=oc)
expect(owner,"/api/v1/team/members/"+second+"/revoke",204,method="POST",csrf=oc)
expect(member,"/api/v1/auth/me",403)
expect(member,"/api/v1/team/members",403)
expect(owner,"/api/v1/team/members/"+second+"/reviewer",404,method="POST",csrf=oc,
  body={"enabled":True})
after=expect(owner,"/api/v1/team/members")
assert sum(1 for m in after if m["status"]=="ACTIVE")==1
expect(owner,"/api/v1/auth/logout",204,method="POST",csrf=oc)
expect(owner,"/api/v1/team/members",401)
print("PASS V15: PG18 2-Owner invitation, one-use token, CSRF, separate login, reviewer no self-grant, reviewer revoke, membership immediate revocation, owner retained")

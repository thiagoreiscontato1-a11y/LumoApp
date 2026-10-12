"""Exercise actual Java SQL on SQLite: v7 migration, event isolation and paging."""
import pathlib,re,sqlite3
source=(pathlib.Path(__file__).parents[1]/'app/src/main/java/com/hisho/tether/Jobs.java').read_text()
main=(pathlib.Path(__file__).parents[1]/'app/src/main/java/com/hisho/tether/MainActivity.java').read_text()
db=sqlite3.connect(':memory:')
create=re.search(r'd.execSQL\("(CREATE TABLE jobs .*?)"\)',source).group(1)
# Existing v7 install containing an old photo.
v7=create.replace(", event_id TEXT NOT NULL DEFAULT ''",'')
db.execute(v7)
db.execute("INSERT INTO jobs(id,file,name,settings,state,original,edited,album,content_hash) VALUES('old','x','old.jpg','{}','done','o','e','old album','same')")
for sql in re.findall(r'd.execSQL\("(ALTER TABLE jobs ADD COLUMN event_id .*?|CREATE TABLE IF NOT EXISTS local_events .*?|CREATE INDEX IF NOT EXISTS idx_jobs_event .*?)"\)',source): db.execute(sql)
assert db.execute("SELECT event_id,original FROM jobs WHERE id='old'").fetchone()==('','o')
for sql in re.findall(r'd.execSQL\("(CREATE TABLE IF NOT EXISTS fotto_uploads .*?)"\)',source):db.execute(sql)
for event in ['A','B']:
 db.execute('INSERT INTO local_events VALUES(?,?,?)',(event,'Same name',1))
 for i in range(140):db.execute("INSERT INTO jobs(id,file,name,settings,state,original,edited,album,content_hash,event_id) VALUES(?,?,?,'{}','done','o','e',?,?,?)",(event+str(i),'x',event+str(i),'album '+event,'same',event))

def expression(expr,event):
 expr=expr.replace('android.database.DatabaseUtils.sqlEscapeString(event)', '"\'"+event+"\'"').replace('eventSql()', '"event_id=\'"+event+"\'"')
 return eval(expr,{'event':event,'__builtins__':{}})
# Actual prepared upload query, including retries and pagination.
block=source.split('synchronized String[] nextFotto(String galleryId,long after,String event){')[1].split('\n }')[0]
sql_expr=re.search(r'String sql=(.*?);',block).group(1)
for event in ['A','B','']:
 sql=expression(sql_expr,event);row=db.execute(sql,('remote',0)).fetchone();assert row[0].startswith(event) if event else row[0]=='old'
 if event:
  seen=[];after=0
  while (row:=db.execute(sql,('remote',after)).fetchone()):seen.append(row[0]);after=row[3]
  assert len(seen)==140 and all(x.startswith(event) for x in seen)
# Exact content duplicate query is scoped; a different event cannot suppress imports.
expr=re.search(r'rawQuery\(("SELECT id FROM jobs WHERE .*?content_hash=\? LIMIT 1"),new String',source).group(1)
assert db.execute(expression(expr,'C'),('same',)).fetchone() is None
assert db.execute(expression(expr,'B'),('same',)).fetchone()[0].startswith('B')
# Actual album query isolates same-named events and legacy pictures.
expr=re.search(r'rawQuery\(("SELECT DISTINCT album .*?"),null',source).group(1)
assert db.execute(expression(expr,'A')).fetchall()==[('album A',)]
assert db.execute(expression(expr,'')).fetchall()==[('old album',)]
# Cleanup candidate SQL never targets another event, nor review copies.
db.execute("UPDATE jobs SET state='review' WHERE id='A0'")
expr=re.search(r'rawQuery\(("SELECT id,edited FROM jobs .*?"),null',source).group(1)
rows=db.execute(expression(expr,'A')).fetchall()
assert len(rows)==139 and all(x[0].startswith('A') and x[0]!='A0' for x in rows)
# Source integration gates safeguard routing, persistent job association and async pages.
for snippet in ['v.put("event_id",event)','folder=outputFolder(id,folder)','event+"/"+folder']:
 assert snippet in source,snippet
for snippet in ['if(!requireEvent())return','if(!requireEvent()||importingEvent)return','final String event=database.activeEvent()','queryGallery(album,limit,before,event)','galleryGeneration++','putBoolean("fottoAuto",false)']:
 assert snippet in main,snippet
print('PASS: v7 migration retains photos; events isolate albums, duplicates, 140-photo upload pages and cleanup; capture/import and async gallery guards present.')

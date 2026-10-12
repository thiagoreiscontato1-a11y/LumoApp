import sqlite3

db=sqlite3.connect(':memory:')
db.execute("CREATE TABLE jobs (id TEXT PRIMARY KEY, file TEXT NOT NULL, name TEXT NOT NULL, settings TEXT NOT NULL, original TEXT, edited TEXT, state TEXT NOT NULL, error TEXT)")
db.execute("CREATE TABLE fotto_uploads (job_id TEXT NOT NULL, gallery_id TEXT NOT NULL, state TEXT NOT NULL, media_id TEXT, error TEXT, attempts INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(job_id,gallery_id))")
for i in range(1,5):
    db.execute("INSERT INTO jobs(id,file,name,settings,edited,state) VALUES(?,?,?,?,?,?)",(f'j{i}','x',f'IMG_{i}.jpg','{}',f'content://edited/{i}','done'))
# j1 already sent, j2 interrupted, j3 is an error with attempts left, j4 has never been queued.
db.execute("INSERT INTO fotto_uploads VALUES(?,?,?,?,?,?,?)",('j1','g1','done','m1','',1,1))
db.execute("INSERT INTO fotto_uploads VALUES(?,?,?,?,?,?,?)",('j2','g1','uploading','','',1,1))
db.execute("INSERT INTO fotto_uploads VALUES(?,?,?,?,?,?,?)",('j3','g1','error','','network',2,1))
db.execute("UPDATE fotto_uploads SET state='error', error='interrupted' WHERE gallery_id=? AND state='uploading'",('g1',))
sql="SELECT j.id,j.name,j.edited,j.rowid FROM jobs j LEFT JOIN fotto_uploads f ON f.job_id=j.id AND f.gallery_id=? WHERE j.state='done' AND j.edited IS NOT NULL AND j.rowid>? AND (f.state IS NULL OR (f.state='error' AND f.attempts<3)) ORDER BY j.rowid LIMIT 1"
seen=[]
while True:
    row=db.execute(sql,('g1',0)).fetchone()
    if not row: break
    seen.append(row[0])
    db.execute("INSERT OR REPLACE INTO fotto_uploads(job_id,gallery_id,state,media_id,error,attempts,updated) VALUES(?,?,?,?,?,?,?)",(row[0],'g1','done','m','',1,2))
assert seen==['j2','j3','j4'], seen
assert 'j1' not in seen
print('PASS: Fotto queue skips completed uploads and recovers interrupted/error/new edited jobs.')

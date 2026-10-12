import sqlite3,re,pathlib
s=(pathlib.Path(__file__).resolve().parents[1]/'app/src/main/java/com/hisho/tether/Jobs.java').read_text()
d=sqlite3.connect(':memory:')
d.execute(re.search(r'd.execSQL\("(CREATE TABLE[^\"]+)"',s)[1])
for i,state,uri in [('a','editing','content://a'),('b','originalError','content://b'),('c','done','content://c'),('d','error',None)]:d.execute('INSERT INTO jobs(id,file,name,settings,state,original) VALUES(?,?,?,?,?,?)',(i,'x','x','{}',state,uri))
for statement in re.findall(r'execSQL\("(UPDATE jobs[^\"]+)"',s):d.execute(statement)
assert dict(d.execute('SELECT id,state FROM jobs'))=={'a':'ready','b':'original','c':'done','d':'original'}
print('PASS: recovery preserves completed jobs and retries partial original/edited writes in their proper phase.')

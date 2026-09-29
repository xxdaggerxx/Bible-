import json, sqlite3, os, re
d = json.load(open('KJV.json'))
names = ["Genesis","Exodus","Leviticus","Numbers","Deuteronomy","Joshua","Judges","Ruth","1 Samuel","2 Samuel","1 Kings","2 Kings","1 Chronicles","2 Chronicles","Ezra","Nehemiah","Esther","Job","Psalms","Proverbs","Ecclesiastes","Song of Solomon","Isaiah","Jeremiah","Lamentations","Ezekiel","Daniel","Hosea","Joel","Amos","Obadiah","Jonah","Micah","Nahum","Habakkuk","Zephaniah","Haggai","Zechariah","Malachi","Matthew","Mark","Luke","John","Acts","Romans","1 Corinthians","2 Corinthians","Galatians","Ephesians","Philippians","Colossians","1 Thessalonians","2 Thessalonians","1 Timothy","2 Timothy","Titus","Philemon","Hebrews","James","1 Peter","2 Peter","1 John","2 John","3 John","Jude","Revelation"]
osis = "Gen Exod Lev Num Deut Josh Judg Ruth 1Sam 2Sam 1Kgs 2Kgs 1Chr 2Chr Ezra Neh Esth Job Ps Prov Eccl Song Isa Jer Lam Ezek Dan Hos Joel Amos Obad Jonah Mic Nah Hab Zeph Hag Zech Mal Matt Mark Luke John Acts Rom 1Cor 2Cor Gal Eph Phil Col 1Thess 2Thess 1Tim 2Tim Titus Phlm Heb Jas 1Pet 2Pet 1John 2John 3John Jude Rev".split()
assert len(names)==66==len(osis)==len(d['books'])
os.makedirs('out', exist_ok=True)
p='out/kjv.db'
if os.path.exists(p): os.remove(p)
db=sqlite3.connect(p)
db.executescript("""
CREATE TABLE android_metadata (locale TEXT DEFAULT 'en_US');
INSERT INTO android_metadata VALUES ('en_US');
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE books(id INTEGER PRIMARY KEY, name TEXT NOT NULL, osis TEXT NOT NULL, chapters INTEGER NOT NULL);
CREATE TABLE verses(id INTEGER PRIMARY KEY, book INTEGER NOT NULL, chapter INTEGER NOT NULL, verse INTEGER NOT NULL, text TEXT NOT NULL);
CREATE INDEX verses_bc ON verses(book, chapter);
CREATE VIRTUAL TABLE verses_fts USING fts4(text, content="verses");
CREATE TABLE xrefs(from_id INTEGER NOT NULL, to_start INTEGER NOT NULL, to_end INTEGER NOT NULL, votes INTEGER NOT NULL);
""")
db.executemany("INSERT INTO meta VALUES(?,?)",[("code","KJV"),("name","King James Version (1769)"),("copyright","Public domain"),("schema","1")])
rows=[]
for bi,bk in enumerate(d['books'],1):
    db.execute("INSERT INTO books VALUES(?,?,?,?)",(bi,names[bi-1],osis[bi-1],len(bk['chapters'])))
    for ch in bk['chapters']:
        for v in ch['verses']:
            t=re.sub(r'\s+',' ',v['text']).strip()
            rows.append((bi*1000000+ch['chapter']*1000+v['verse'],bi,ch['chapter'],v['verse'],t))
db.executemany("INSERT INTO verses VALUES(?,?,?,?,?)",rows)
db.execute("INSERT INTO verses_fts(verses_fts) VALUES('rebuild')")
idx={o:i+1 for i,o in enumerate(osis)}
def vid(s):
    b,c,v=s.split('.'); return idx[b]*1000000+int(c)*1000+int(v)
valid=set(r[0] for r in rows)
per={}
with open('xref.txt') as f:
    next(f)
    for line in f:
        a,b,votes=line.rstrip('\n').split('\t')
        votes=int(votes)
        if votes<1: continue
        fr=vid(a)
        if '-' in b: s,e=b.split('-'); s,e=vid(s),vid(e)
        else: s=e=vid(b)
        if fr not in valid or s not in valid: continue
        per.setdefault(fr,[]).append((fr,s,e,votes))
x=[]
for fr,l in per.items():
    l.sort(key=lambda r:-r[3]); x.extend(l[:30])
db.executemany("INSERT INTO xrefs VALUES(?,?,?,?)",x)
db.execute("CREATE INDEX xrefs_from ON xrefs(from_id)")
db.commit()
print(len(rows), len(x))
print(db.execute("SELECT v.id,v.text FROM verses_fts f JOIN verses v ON v.id=f.rowid WHERE verses_fts MATCH '\"only begotten son\"' LIMIT 3").fetchall())
db.execute("VACUUM"); db.close()
print(os.path.getsize(p)/1e6,'MB')

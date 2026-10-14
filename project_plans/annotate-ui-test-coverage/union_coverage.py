import xml.etree.ElementTree as ET
def lines(path):
    d={}
    for p in ET.parse(path).getroot().iter('package'):
        if not p.get('name').endswith('ui/annotate'): continue
        for s in p.findall('sourcefile'):
            for l in s.findall('line'):
                k=(s.get('name'),int(l.get('nr'))); d[k]=max(d.get(k,0),int(l.get('ci')))
    return d
j=lines('kmp/build/reports/jacoco/jacoco.xml'); a=lines('kmp/build/reports/jacoco/jacoco-android.xml')
keys=set(j)|set(a)
cov=sum(1 for k in keys if j.get(k,0)>0 or a.get(k,0)>0)
print('jvm   ',sum(1 for k in j if j[k]>0),len(j))
print('android',sum(1 for k in a if a[k]>0),len(a))
print('union ',cov,len(keys),round(100*cov/len(keys),1))

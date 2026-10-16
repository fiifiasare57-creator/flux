# Rebuilds website/index.html (self-contained, images embedded). Needs: pip install pillow
import base64, io
from PIL import Image
import os
HERE=os.path.dirname(os.path.abspath(__file__))
A=HERE+"/assets/"
F=["soccer","food","kayak","tokyo","doc","folder","pdf","types"]
def uri(path, maxw, icon=False):
    im=Image.open(path); im=im.convert("RGBA") if im.mode in("RGBA","P","LA") else im.convert("RGB")
    if im.mode=="RGBA":
        bg=Image.new("RGB",im.size,(255,255,255)); bg.paste(im,mask=im.split()[3]); im=bg
    im.thumbnail((maxw,maxw*2))
    b=io.BytesIO()
    if icon: im.save(b,"PNG",optimize=True); m="image/png"
    else: im.save(b,"JPEG",quality=70,optimize=True,progressive=True); m="image/jpeg"
    return f"data:{m};base64,"+base64.b64encode(b.getvalue()).decode()
img={k:uri(A+k+".png",640 if k in("kayak","tokyo") else 360,icon=k in("doc","folder","pdf","types")) for k in F}
logo="data:image/png;base64,"+base64.b64encode(open(A+"logo.png","rb").read()).decode()
def fly(k,name,cls=""): return f'<div class="fly" data-name="{name}"><div class="t {cls}"><img src="{img[k]}" alt=""></div><small>{name}</small></div>'
FLY="".join([fly("kayak","Kayaks.jpg"),fly("soccer","Match.mp4","vid"),fly("pdf","Notes.pdf","ic"),fly("tokyo","Shibuya.jpg"),fly("folder","Project","ic"),fly("food","Dinner.jpg")])
tr=lambda k,c="": f'<div class="{c}"><img src="{img[k]}" alt=""></div>'
TRACK="".join([tr("kayak"),tr("types","ic"),tr("tokyo"),tr("doc","ic"),tr("food"),tr("soccer"),tr("folder","ic"),tr("kayak")])
html=open(HERE+"/template.html").read().replace("{{LOGO}}",logo).replace("{{FLY}}",FLY).replace("{{TRACK}}",TRACK)
open(HERE+"/index.html","w").write(html); print("size KB:",len(html)//1024)

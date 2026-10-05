"""Draw the original coding远程 device/terminal mark at all distribution sizes."""
from pathlib import Path
from PIL import Image,ImageDraw

ROOT=Path(__file__).resolve().parents[1]

def draw():
    scale=4
    im=Image.new('RGBA',(1024*scale,1024*scale),'#e8f0e5')
    d=ImageDraw.Draw(im)
    def box(bounds,fill,radius):d.rounded_rectangle(tuple(int(x*scale) for x in bounds),radius*scale,fill=fill)
    def line(points,fill,width):
        points=[(x*scale,y*scale) for x,y in points];d.line(points,fill=fill,width=width*scale,joint='curve')
        r=width*scale/2
        for x,y in (points[0],points[-1]):d.ellipse((x-r,y-r,x+r,y+r),fill=fill)
    box((180,225,775,700),'#225840',84)
    box((219,265,735,635),'#f8faf5',49)
    line([(300,359),(369,419),(300,479)],'#225840',30)
    line([(425,480),(507,480)],'#225840',30)
    line([(384,760),(551,760)],'#225840',34)
    line([(468,704),(468,751)],'#225840',34)
    box((615,427,846,795),'#225840',50)
    box((638,452,823,765),'#d9e8cc',29)
    line([(702,488),(758,488)],'#225840',12)
    line([(685,600),(718,630),(777,568)],'#225840',20)
    im=im.resize((1024,1024),Image.Resampling.LANCZOS)
    out=ROOT/'packaging/product';out.mkdir(exist_ok=True)
    im.save(out/'icon.png');im.save(out/'icon.ico',sizes=[(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)])
    return im

if __name__=='__main__':draw()

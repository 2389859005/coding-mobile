"""Shared desktop colours: subdued accents, tinted surfaces and light/dark modes."""
import colorsys
import customtkinter as ctk

STYLES = {'雾蓝': 'mist', '黛青': 'slate', '暮紫': 'plum', '松石': 'teal', '琥珀': 'amber', '石墨': 'graphite'}
DESIGNS = {'经典 · 圆角卡片':'classic','纸页 · 阅读风格':'paper','紧凑 · 直角面板':'compact'}
HUES = {'mist': 222, 'slate': 202, 'plum': 274, 'teal': 167, 'amber': 34, 'graphite': 220}
BG=[]; CARD=[]; SOFT=[]; TINT=[]; LINE=[]; INK=[]; MUTED=[]; ACCENT=[]; ACCENT_DARK=[]; DANGER=[]; RUN=[]; WAIT=[]; WAIT_BG=[]

def color(value):
    return value[1 if ctk.get_appearance_mode() == 'Dark' else 0] if isinstance(value, (tuple, list)) else value

def palette(style='mist'):
    hue = HUES.get(style, 222) / 360
    chroma = .03 if style == 'graphite' else 1
    def tone(s, l):
        return '#%02X%02X%02X' % tuple(round(v * 255) for v in colorsys.hls_to_rgb(hue, l, s * chroma))
    def luminance(value):
        channels=[int(value[i:i+2],16)/255 for i in (1,3,5)]
        channels=[x/12.92 if x<=.04045 else ((x+.055)/1.055)**2.4 for x in channels]
        return sum(x*w for x,w in zip(channels,(.2126,.7152,.0722)))
    primary=.43
    while 1.05/(luminance(tone(.43,primary))+.05)<4.6:primary-=.005
    values = {
        'BG':['#F4F5F7','#15181D'], 'CARD':['#FFFFFF','#1E2228'],
        'SOFT':['#ECEEF1','#262B32'], 'TINT':['#E8EBF6','#2A3250'],
        'LINE':['#E1E4E9','#2E333B'], 'INK':['#1B2230','#E6E8EC'],
        'MUTED':['#5F6876','#9AA3AF'], 'ACCENT':['#3A4C9C','#9DB0F0'],
        'ACCENT_DARK':['#304084','#8A9DE0'], 'DANGER':['#B03F3A','#EE8F86'],
        'RUN':['#10776A','#4FC2B0'], 'WAIT':['#9A6210','#E2A64B'],
        'WAIT_BG':['#FBF3E4','#33291A'],
    }
    if style != 'mist':
        values.update(ACCENT=[tone(.43,primary),tone(.60,.76)], TINT=[tone(.65,.90),tone(.27,.245)], ACCENT_DARK=[tone(.43,primary-.06),tone(.52,.69)])
    return values

def apply(mode='system', style='mist', design='classic'):
    values_by_name=palette(style)
    if design=='paper':values_by_name.update(BG=['#F4EFE5','#211E19'],CARD=['#FFFCF5','#2B2720'],SOFT=['#EAE2D2','#363025'],LINE=['#D9CCB5','#4B4133'],INK=['#302C24','#EEE4CF'],MUTED=['#706452','#BCAF98'])
    elif design=='compact':values_by_name.update(BG=['#EDF1F3','#101417'],SOFT=['#E0E7EB','#252E34'],LINE=['#A5B5C0','#46545E'])
    for name, values in values_by_name.items():
        globals()[name][:] = values
    ctk.set_default_color_theme('blue')
    ctk.ThemeManager.theme['CTkButton'].update(fg_color=ACCENT, hover_color=ACCENT_DARK, text_color=['#FFFFFF','#182130'])
    ctk.ThemeManager.theme['CTkSwitch'].update(progress_color=ACCENT)
    ctk.set_appearance_mode({'system':'System','light':'Light','dark':'Dark'}.get(mode,'System'))

apply()

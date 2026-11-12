"""Cached local Office-to-PDF conversion. Files never leave the paired PC."""
import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
import mimetypes
import os
from pathlib import Path
import shutil
import subprocess
import threading
import time

OFFICE_SCRIPT = r'''
param([string]$InputPath,[string]$OutputPath)
$ErrorActionPreference='Stop'
$kind=[IO.Path]::GetExtension($InputPath).ToLowerInvariant()
$app=$null;$document=$null;$previousSecurity=$null;$previousAlerts=$null;$previousLinks=$null;$owned=$false
Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public static class OfficeWindowOwner { [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint process); }'
function Owns-Office($instance,$known) { try { [uint32]$officeProcess=0; [void][OfficeWindowOwner]::GetWindowThreadProcessId([IntPtr]$instance.Hwnd,[ref]$officeProcess); return $officeProcess -gt 0 -and $known -notcontains $officeProcess } catch { return $known.Count -eq 0 } }
$processName=if($kind -in '.ppt','.pptx','.odp'){'POWERPNT'}elseif($kind -in '.xls','.xlsx','.ods'){'EXCEL'}else{'WINWORD'}
$existing=@(Get-Process -Name $processName -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id)
try {
 if($processName -eq 'POWERPNT'){
  $app=New-Object -ComObject PowerPoint.Application
  $owned=Owns-Office $app $existing
  $previousSecurity=$app.AutomationSecurity;$app.AutomationSecurity=3
  $document=$app.Presentations.Open($InputPath,$true,$false,$false)
  $document.SaveAs($OutputPath,32)
 }elseif($processName -eq 'EXCEL'){
  $app=New-Object -ComObject Excel.Application
  $owned=Owns-Office $app $existing
  if($owned){$app.Visible=$false};$previousAlerts=$app.DisplayAlerts;$previousLinks=$app.AskToUpdateLinks;$app.DisplayAlerts=$false;$app.AskToUpdateLinks=$false
  $previousSecurity=$app.AutomationSecurity;$app.AutomationSecurity=3
  $document=$app.Workbooks.Open($InputPath,0,$true)
  $document.ExportAsFixedFormat(0,$OutputPath)
 }else{
  $app=New-Object -ComObject Word.Application
  $owned=Owns-Office $app $existing
  if($owned){$app.Visible=$false};$previousAlerts=$app.DisplayAlerts;$app.DisplayAlerts=0
  $previousSecurity=$app.AutomationSecurity;$app.AutomationSecurity=3
  $missing=[Type]::Missing;$document=$app.Documents.Open($InputPath,$false,$true,$false,$missing,$missing,$missing,$missing,$missing,$missing,$missing,$false)
  $document.ExportAsFixedFormat($OutputPath,17)
 }
}finally{
 if($null -ne $document){try{$document.Close($false)}catch{};[void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($document)}
 if($null -ne $app){
  if($null -ne $previousSecurity){try{$app.AutomationSecurity=$previousSecurity}catch{}}
  if($null -ne $previousAlerts){try{$app.DisplayAlerts=$previousAlerts}catch{}}
  if($null -ne $previousLinks){try{$app.AskToUpdateLinks=$previousLinks}catch{}}
  if($owned){try{$app.Quit()}catch{}}
  [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($app)
 }
 [GC]::Collect();[GC]::WaitForPendingFinalizers()
}
'''


class DocumentPreview:
    def __init__(self, directory):
        self.root=Path(directory)/'document-previews';self.root.mkdir(parents=True,exist_ok=True)
        self.pool=ThreadPoolExecutor(max_workers=1,thread_name_prefix='document-preview')
        self.jobs={};self.lock=threading.Lock()

    @staticmethod
    def revision(path):
        stat=path.stat()
        return hashlib.sha256(f'{path}:{stat.st_mtime_ns}:{stat.st_ctime_ns}:{stat.st_size}'.encode()).hexdigest()

    def request(self,path,query):
        revision=self.revision(path)
        rendered_revision='pdf:'+revision
        if query.get('known',[''])[0]==rendered_revision:return {'unchanged':True,'revision':rendered_revision}
        target=self.root/(revision+'.pdf')
        if not target.exists():
            with self.lock:
                future=self.jobs.get(revision)
                if future is None:
                    future=self.pool.submit(self.convert,path,target);self.jobs[revision]=future
            if not future.done():return {'name':path.name,'previewState':'preparing','revision':revision,'retryAfterMs':300}
            try:future.result()
            finally:
                with self.lock:self.jobs.pop(revision,None)
        value={'name':path.name,'mime':'application/pdf','originalMime':mimetypes.guess_type(path.name)[0],
                'size':target.stat().st_size,'revision':rendered_revision,'rendered':True}
        if value['size']>4*1024*1024:value['download']=True
        else:value['data']=base64.b64encode(target.read_bytes()).decode()
        return value

    def cached_path(self,path):
        target=self.root/(self.revision(path)+'.pdf')
        if not target.is_file():raise ValueError('文档预览尚未准备好，请重试')
        return target

    def convert(self,path,target):
        # Prefer an isolated headless office profile when LibreOffice is installed.
        candidates=[shutil.which('soffice'),r'C:\Program Files\LibreOffice\program\soffice.exe']
        libre=next((str(value) for value in candidates if value and Path(value).is_file()),None)
        if libre:
            work=self.root/(target.stem+'-work');work.mkdir(exist_ok=True)
            profile=work/'profile'
            command=[libre,'-env:UserInstallation='+profile.as_uri(),'--headless','--convert-to','pdf','--outdir',str(work),str(path)]
            subprocess.run(command,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=90,check=True,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
            result=work/(path.stem+'.pdf')
            if not result.is_file():raise ValueError('Office 文件无法转换')
            result.replace(target)
        elif os.name=='nt':
            script=self.root/'office-export.ps1'
            if not script.exists() or script.read_text('utf-8-sig')!=OFFICE_SCRIPT:script.write_text(OFFICE_SCRIPT,encoding='utf-8-sig')
            temporary=target.with_suffix('.working.pdf')
            result=subprocess.run(['powershell.exe','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',str(script),'-InputPath',str(path),'-OutputPath',str(temporary)],
                stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=90,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
            if result.returncode or not temporary.is_file():raise ValueError('电脑未提供可用的 Office 页面渲染，请选择其他应用打开原文件')
            temporary.replace(target)
        else:raise ValueError('需要电脑上的 Office 或 LibreOffice 来保留文档版式')
        with target.open('rb') as stream:valid=stream.read(5)==b'%PDF-'
        if not valid:target.unlink(missing_ok=True);raise ValueError('文档预览生成失败')
        # Cache expiry only touches this converter's own PDFs.
        for old in self.root.glob('*.pdf'):
            if old!=target and time.time()-old.stat().st_mtime>7*86400:old.unlink(missing_ok=True)

    def close(self):
        self.pool.shutdown(wait=False,cancel_futures=True)


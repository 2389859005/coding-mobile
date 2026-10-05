// Embedded userspace Tailscale node. No system VPN, daemon, or elevated privileges.
package main

import (
 "context"
 "encoding/json"
 "flag"
 "fmt"
 "log"
 "net"
 "net/http"
 "net/http/httputil"
 "net/netip"
 "net/url"
 "os"
 "strings"
 "path/filepath"
 "runtime"
 "sync"
 "time"

 "tailscale.com/tsnet"
 "tailscale.com/net/netmon"
)

func main() {
 dir:=flag.String("state","","private state directory"); hostname:=flag.String("hostname","coding-mobile","node name"); upstream:=flag.String("upstream","","desktop loopback upstream"); secretFile:=flag.String("secret-file","","local API secret file"); flag.Parse()
 secret,err:=os.ReadFile(*secretFile);if err!=nil {log.Fatal("missing local API credential")};key:=strings.TrimSpace(string(secret));if len(key)<32 {log.Fatal("invalid local credential")}
 if runtime.GOOS=="android" {netmon.RegisterInterfaceGetter(func()([]netmon.Interface,error){
  var rows []struct{Name string;Index int;MTU int;Up bool;Loopback bool;Addresses []string}
  data,err:=os.ReadFile(filepath.Join(*dir,"interfaces.json"));if err!=nil{return nil,err};if err=json.Unmarshal(data,&rows);err!=nil{return nil,err}
  out:=make([]netmon.Interface,0,len(rows));for _,r:=range rows{flags:=net.FlagMulticast;if r.Up{flags|=net.FlagUp};if r.Loopback{flags|=net.FlagLoopback};row:=netmon.Interface{Interface:&net.Interface{Name:r.Name,Index:r.Index,MTU:r.MTU,Flags:flags},AltAddrs:[]net.Addr{}};for _,addr:=range r.Addresses{ip,network,err:=net.ParseCIDR(addr);if err==nil{network.IP=ip;row.AltAddrs=append(row.AltAddrs,network)}};out=append(out,row)};return out,nil
 })}
 node:=&tsnet.Server{Dir:*dir,Hostname:*hostname,Logf:log.Printf};if err=node.Start();err!=nil {log.Fatal(err)};defer node.Close()
 local,err:=node.LocalClient();if err!=nil {log.Fatal(err)}
 var mu sync.Mutex;origin:="";listenerStarted:=false
 transport:=&http.Transport{DialContext:node.Dial,ResponseHeaderTimeout:120*time.Second}
 mux:=http.NewServeMux()
 mux.HandleFunc("/status",func(w http.ResponseWriter,r *http.Request){
  status,err:=local.Status(r.Context());if err!=nil {http.Error(w,"network starting",503);return}
  mu.Lock();defer mu.Unlock();json.NewEncoder(w).Encode(map[string]any{"state":status.BackendState,"authUrl":status.AuthURL,"origin":origin,"addresses":status.TailscaleIPs})
 })
 mux.HandleFunc("/login",func(w http.ResponseWriter,r *http.Request){if r.Method!="POST" {http.Error(w,"POST required",405);return};if err:=local.StartLoginInteractive(r.Context());err!=nil {http.Error(w,"login unavailable",503);return};json.NewEncoder(w).Encode(map[string]bool{"ok":true})})
 mux.HandleFunc("/network-changed",func(w http.ResponseWriter,r *http.Request){if r.Method!="POST"{http.Error(w,"POST required",405);return};node.Sys().NetMon.Get().InjectEvent();json.NewEncoder(w).Encode(map[string]bool{"ok":true})})
 server:=http.Server{ReadHeaderTimeout:10*time.Second,Handler:http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
  if r.Header.Get("Proxy-Authorization")!="Bearer "+key && r.Header.Get("Authorization")!="Bearer "+key {http.Error(w,"unauthorized",401);return}
  if !r.URL.IsAbs(){mux.ServeHTTP(w,r);return}
  host:=r.URL.Hostname();address,e:=netip.ParseAddr(host)
  if e!=nil || !netip.MustParsePrefix("100.64.0.0/10").Contains(address) || r.URL.Scheme!="http" {http.Error(w,"only private device HTTP allowed",403);return}
  r.Header.Del("Proxy-Authorization");r.RequestURI=""
  // User session Authorization remains scoped to the original destination.
  proxy:=httputil.ReverseProxy{Director:func(out *http.Request){out.URL=r.URL;out.Host=r.Host},Transport:transport,ErrorHandler:func(w http.ResponseWriter,r *http.Request,e error){http.Error(w,"private device unavailable",502)}};proxy.ServeHTTP(w,r)
 })}
 listener,err:=net.Listen("tcp4","127.0.0.1:0");if err!=nil {log.Fatal(err)};fmt.Printf("{\"port\":%d}\n",listener.Addr().(*net.TCPAddr).Port)
 if *upstream!="" {go func(){for {time.Sleep(time.Second);state,e:=local.Status(context.Background());if e!=nil || state.BackendState!="Running" {continue};mu.Lock();if listenerStarted {mu.Unlock();continue};listenerStarted=true;mu.Unlock()
  ln,e:=node.Listen("tcp",":18787");if e!=nil {mu.Lock();listenerStarted=false;mu.Unlock();continue}
  target,e:=url.Parse(*upstream);if e!=nil || target.Hostname()!="127.0.0.1" {log.Fatal("upstream must be loopback")}
  mu.Lock();for _,ip:=range state.TailscaleIPs {if ip.Is4(){origin="http://"+ip.String()+":18787";break}};mu.Unlock()
  proxy:=httputil.NewSingleHostReverseProxy(target);go http.Serve(ln,proxy)
 }}()}
 if err=server.Serve(listener);err!=nil {log.Fatal(err)}
}

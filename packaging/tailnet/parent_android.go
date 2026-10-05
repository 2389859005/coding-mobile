package main

import (
 "os"
 "time"
)

// Android owns the application lifecycle; never leave a detached network process.
func init(){parent:=os.Getppid();go func(){for{time.Sleep(3*time.Second);if os.Getppid()!=parent{os.Exit(0)}}}()}

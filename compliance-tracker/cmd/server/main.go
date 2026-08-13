// The entry point for the Compliance Tracker agent runtime.
package main

import (
	"github.com/eclipse-cfm/cfm/common/runtime"
	"github.com/metaform/cx-ve/compliance-tracker/launcher"
)

func main() {
	launcher.LaunchAndWaitSignal(runtime.CreateSignalShutdownChan())
}

package setup

import (
	"encoding/xml"
	"errors"
	"fmt"
	"os"
	"strings"
	"testing"
)

func TestBootPolicies(t *testing.T) {
	for _, initial := range []int{0, 1, 2} {
		t.Run(fmt.Sprint(initial), func(t *testing.T) {
			f := newFake()
			s, root := newSetup(t, f)
			s.PackageRestrictions = root + "/packages.xml"
			states := []int{initial, 0}
			installed := []bool{true, false}
			persist := func() {
				body := "<package-restrictions>"
				for i, p := range consoleBootPolicies {
					if !installed[i] {
						continue
					}
					body += fmt.Sprintf(`<pkg name="%s">`, p.pkg)
					if states[i] != 0 {
						tag := "enabled-components"
						if states[i] == 2 {
							tag = "disabled-components"
						}
						body += fmt.Sprintf(`<%s><item name="%s"/></%s>`, tag, p.receiver, tag)
					}
					body += "</pkg>"
				}
				write(t, s.PackageRestrictions, body+"</package-restrictions>")
			}
			fail := false
			f.handlers["pm"] = func(a []string) (string, error) {
				if len(a) != 4 || a[1] != "--user" || a[2] != "0" {
					t.Fatalf("bad pm args: %q", a)
				}
				if fail {
					return "Error: injected", errors.New("binder unavailable")
				}
				for i, p := range consoleBootPolicies {
					if a[3] != p.pkg+"/"+p.receiver {
						continue
					}
					switch a[0] {
					case "disable":
						states[i] = 2
					case "enable":
						states[i] = 1
					case "default-state":
						states[i] = 0
					default:
						t.Fatalf("bad pm verb %q", a[0])
					}
					persist()
					return "Component new state", nil
				}
				t.Fatalf("unexpected component %q", a[3])
				return "", nil
			}
			persist()
			s.ApplyBootPolicies()
			if states[0] != 2 {
				t.Fatal("TV boot receiver not disabled")
			}
			if f.count("pm ") != map[bool]int{true: 0, false: 1}[initial == 2] {
				t.Fatal("unexpected initial PM calls", f.calls)
			}
			f.reset()
			s.ApplyBootPolicies()
			if f.count("pm ") != 0 {
				t.Fatal("unchanged state not idempotent")
			}
			// An optional app installed by the wizard later is handled on its next job.
			installed[1] = true
			persist()
			s.ApplyBootPolicies()
			if states[1] != 2 {
				t.Fatal("late install ignored")
			}
			f.props["persist.andr36oid.ram.boot_vlc"] = "0"
			s.ApplyBootPolicies()
			if states[0] != initial || states[1] != 2 {
				t.Fatal("independent opt-out failed", states)
			}
			f.props["persist.andr36oid.ram.boot_mupen"] = "0"
			fail = true
			s.ApplyBootPolicies()
			if states[1] != 2 {
				t.Fatal("failed PM unexpectedly changed state")
			}
			if _, err := os.Stat(s.state("boot-policy-mupen.json")); err != nil {
				t.Fatal("failed restore lost prior state")
			}
			fail = false
			s.ApplyBootPolicies()
			if states[1] != 0 {
				t.Fatal("retry did not restore original")
			}
			if _, err := os.Stat(s.state("boot-policy-mupen.json")); !os.IsNotExist(err) {
				t.Fatal("restore marker not removed")
			}
			f.reset()
			s.ApplyBootPolicies()
			if f.count("pm ") != 0 {
				t.Fatal("opt-out keeps rewriting")
			}
			delete(f.props, "persist.andr36oid.ram.boot_mupen")
			s.ApplyBootPolicies()
			if states[1] != 2 {
				t.Fatal("re-enable ignored")
			}
		})
	}
}

func TestBootPoliciesDoNotChangeOnUntrustedState(t *testing.T) {
	for _, input := range []string{"", "<package-restrictions>", "<wrong-root/>", "<package-restrictions/>"} {
		f := newFake()
		s, root := newSetup(t, f)
		s.PackageRestrictions = root + "/packages.xml"
		write(t, s.PackageRestrictions, input)
		s.ApplyBootPolicies()
		if f.count("pm ") != 0 {
			t.Fatal("missing/invalid state changed PM")
		}
	}
	f := newFake()
	s, root := newSetup(t, f)
	s.PackageRestrictions = root + "/packages.xml"
	p := consoleBootPolicies[0]
	write(t, s.PackageRestrictions, `<package-restrictions><pkg name="`+p.pkg+`"/></package-restrictions>`)
	write(t, s.state("boot-policy-vlc.json"), `{"version":1,"component":"other","previous":0}`)
	s.ApplyBootPolicies()
	if f.count("pm ") != 0 {
		t.Fatal("corrupt marker changed PM")
	}
	write(t, s.state("boot-policy-vlc.json"), `{"version":1,"component":"`+p.pkg+`/`+p.receiver+`","previous":1}`)
	f.props["persist.andr36oid.ram.boot_vlc"] = "0"
	// Durable marker with no PM update models a cut before the disable call.
	s.ApplyBootPolicies()
	if f.count("pm enable --user 0 ") != 1 {
		t.Fatal("interrupted disable cannot restore", f.calls)
	}
}

func TestComponentOverrideDoesNotReadPackageEnabledAsComponent(t *testing.T) {
	var p componentOverrides
	if err := xml.Unmarshal([]byte(`<package-restrictions><pkg name="a" enabled="3"><enabled-components><item name="a.B"/></enabled-components><disabled-components><item name="a.C"/></disabled-components></pkg></package-restrictions>`), &p); err != nil {
		t.Fatal(err)
	}
	for component, want := range map[string]int{"a.B": 1, "a.C": 2, "a.D": 0} {
		if got, ok := p.state("a", component); !ok || got != want {
			t.Fatalf("%s: %d %v", component, got, ok)
		}
	}
	// Keep the policy narrowly about television, not widget or playback components.
	for _, item := range consoleBootPolicies {
		if strings.Contains(item.receiver, "Widget") || strings.Contains(item.receiver, "Playback") {
			t.Fatal(item)
		}
	}
}

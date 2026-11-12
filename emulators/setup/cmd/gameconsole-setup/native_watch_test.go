package main

import "testing"

func TestNativeWatcherOptOutAndFallbackGuard(t *testing.T) {
	for _, legacy := range []string{"", "1"} {
		for _, value := range []string{"", "0", "1"} {
			calls := 0
			get := func(key string) string {
				calls++
				if key != "persist.andr36oid.ram.native_platform_watch" {
					t.Fatal(key)
				}
				return value
			}
			got := nativeWatcherEnabled(get, legacy)
			if got != (legacy != "1" && value != "0") {
				t.Fatal("incorrect watcher path", legacy, value, got)
			}
			if legacy == "1" && calls != 0 {
				t.Fatal("fallback attempted to enter native path again")
			}
		}
	}
}

package setup

import "testing"

func TestLandscapeSize(t *testing.T) {
	for in, want := range map[string]string{
		"Physical size: 480x640\n":                         "640x480",
		"Physical size: 720x720\n":                         "720x720",
		"Physical size: 640x480\nOverride size: 480x360\n": "480x360",
		"":                      "",
		"Physical size: oops\n": "",
	} {
		if got := landscapeSize(in); got != want {
			t.Errorf("landscapeSize(%q) = %q, want %q", in, got, want)
		}
	}
}

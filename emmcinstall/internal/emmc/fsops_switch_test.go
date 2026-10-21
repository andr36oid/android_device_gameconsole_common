package emmc

import "testing"

func TestSetEmmcSwitch(t *testing.T) {
	for _, c := range []struct{ in, want string }{
		{"# x\nkt_args=dw_mmc_rockchip.emmc_speed=hs200\n", "# x\nkt_args=dw_mmc_rockchip.builtin_emmc=1 dw_mmc_rockchip.emmc_speed=hs200\n"},
		{"kt_args=dw_mmc_rockchip.builtin_emmc=0 a=1\n", "kt_args=dw_mmc_rockchip.builtin_emmc=1 a=1\n"},
		{"kt_args=a=1 dw_mmc_rockchip.builtin_emmc=1\n", "kt_args=a=1 dw_mmc_rockchip.builtin_emmc=1\n"},
		{"# only a comment", "# only a comment\nkt_args=dw_mmc_rockchip.builtin_emmc=1\n"},
	} {
		if got, _ := setEmmcSwitch(c.in); got != c.want {
			t.Errorf("setEmmcSwitch(%q) = %q, want %q", c.in, got, c.want)
		}
	}
	if got, _ := setEmmcSwitch(""); got == "" || !contains(got, "kt_args=dw_mmc_rockchip.builtin_emmc=1\n") {
		t.Errorf("empty file: %q", got)
	}
}

func contains(s, sub string) bool {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}

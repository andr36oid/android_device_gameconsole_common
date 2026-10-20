package emmc

import "fmt"

const align = 2048 // 1 MiB

func roundUp(n int64) int64 { return (n + align - 1) / align * align }

// The partitions andr36oid gets on the eMMC, in this order. BOOT must be the
// first: U-Boot reads mmc 0:1.
var planOrder = []struct {
	name, typ string
	grow      bool // gets room for later, bigger updates
}{
	{"BOOT", TypeBasicData, false},
	{"system", TypeLinux, true},
	{"vendor", TypeLinux, true},
	{"metadata", TypeLinux, false},
	{"misc", TypeLinux, false},
	{"cache", TypeLinux, false},
}

// MakePlan lays out the eMMC (emmcSecs sectors) from the SD card's partitions:
// BOOT, metadata, misc and cache as big as on the SD card; system and vendor a
// quarter bigger (twice as big from BigEmmc on); userdata gets the rest.
func (c *Config) MakePlan(emmcSecs int64, sd []Part) ([]Part, error) {
	if emmcSecs < c.MinEmmc {
		return nil, fmt.Errorf("the eMMC has %d MiB, andr36oid needs one of at least %d MiB",
			emmcSecs*Sector>>20, c.MinEmmc*Sector>>20)
	}
	var parts []Part
	next := int64(LoaderEnd)
	for i, p := range planOrder {
		src, ok := Find(sd, p.name)
		if !ok {
			return nil, fmt.Errorf("the SD card has no %s partition", p.name)
		}
		n := src.Sectors()
		if p.grow {
			if emmcSecs >= c.BigEmmc {
				n *= 2
			} else {
				n += n / 4
			}
		}
		n = roundUp(n)
		parts = append(parts, Part{Num: i + 1, Name: p.name, Type: p.typ, First: next, Last: next + n - 1})
		next += n
	}
	// userdata up to the last whole MiB before the backup GPT
	end := (emmcSecs - 1 - gptTableSecs) / align * align
	if end-next < c.MinUserdata {
		return nil, fmt.Errorf("the eMMC has %d MiB, too little left for userdata (%d MiB)",
			emmcSecs*Sector>>20, (end-next)*Sector>>20)
	}
	parts = append(parts, Part{Num: len(parts) + 1, Name: "userdata", Type: TypeLinux, First: next, Last: end - 1})
	return parts, nil
}

// samePlan reports whether the eMMC's table is the planned one.
func samePlan(got, want []Part) bool {
	if len(got) != len(want) {
		return false
	}
	for i := range got {
		g, w := got[i], want[i]
		if g.Num != w.Num || g.Name != w.Name || g.Type != w.Type || g.First != w.First || g.Last != w.Last {
			return false
		}
	}
	return true
}

package setup

type librarySyncFields struct {
	favorite, playcount, lastplayed, desc, art bool
}

func (s *Setup) librarySyncFields() librarySyncFields {
	return librarySyncFields{
		s.syncEnabled("favorite", true), s.syncEnabled("playcount", true),
		s.syncEnabled("lastplayed", true), s.syncEnabled("desc", true), s.syncEnabled("art", true),
	}
}

func (s *Setup) syncEnabled(name string, def bool) bool {
	v := s.Props.Get("persist.andr36oid.sync." + name)
	if def {
		return v != "0"
	}
	return v == "1"
}

func (f librarySyncFields) merge(a, b djLibraryState) djLibraryState {
	merged := a.merge(b)
	if !f.favorite {
		merged.favorite = a.favorite
	}
	if !f.playcount {
		merged.count = a.count
	}
	if !f.lastplayed {
		merged.played = a.played
	}
	return merged
}

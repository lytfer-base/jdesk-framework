package com.jdesk.frame.settings;

import java.util.Set;

/** A settings page that the dialog's search matches by its display name or by the keywords it returns. */
public interface SearchableConfigurable extends Configurable {

    /** Returns the page's search keywords. */
    Set<String> getSearchKeywords();
}

#!/bin/bash
java -Xmx128m -splash:splashscreen.gif -Djava.security.manager -Djava.security.policy=all_allowed.policy -cp 'lib/*' de.hu_berlin.german.korpling.annis.kickstarter.MainFrame

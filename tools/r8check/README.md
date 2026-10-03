# R8 check for the Claude API library

The release build shrinks the app with R8. The Claude API library (used by the AI chat) reads
annotations and classes at run time, so shrinking can break it while the unit tests, which run
unshrunk, still pass. That happened in 1.6.0 ("JsonMissing cannot be serialized" on the tablet).

`check.sh` builds `Main.java`, a small program that makes the chat's request to a local stand-in
server, reads a reply with citations and builds a follow-up request. It runs it unshrunk, then
shrinks it with the library's own rules and `app/proguard-rules.pro`, runs it again and compares.

Run it after changing `app/proguard-rules.pro` or updating the library (after a release build has
downloaded it):

    JAVA_HOME=/path/to/jdk sh tools/r8check/check.sh

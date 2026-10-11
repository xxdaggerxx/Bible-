Bible aids (AID-1 to AID-12): Jewish customs and feasts, symbols and Bible numbers, written by AI
(Claude, in the 1.22 build session) from public-domain works, traditional view only. People and
places come from the Names & places data (TIPNR) already in study.db.

Each entry:

    == Title
    kind: feast | worship | life | group | rome | symbol | number
    forms: words or phrases that name it, as the versions word them (lower case, comma separated)
    anywhere: yes        marked wherever a form appears (only for words that always mean it)
    anywhere: no         marked only in the verses under refs (for words with other meanings)
    refs: key verses (OSIS book codes: Gen 1:1; Exod 12:1-28; Lev 23)
    not: verses never to mark (optional)
    sources: the works it rests on
    The text: 2 to 4 plain sentences (several lines are joined).

tools/build_aids.py checks the files and writes app/src/main/assets/study/aids.tsv.

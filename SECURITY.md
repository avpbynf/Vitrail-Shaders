# Security Policy

## Supported versions

Everything under 1.0.0 is a pre-release, and only the most recent release gets
fixes: a report against an older build is welcome, and is answered on the
latest one. No older line is maintained beside it.

## What counts as a vulnerability here

Vitrail's attack surface is the shader pack: downloaded content, read, parsed
and translated by this mod. A pack that can touch anything outside its own
folder, keep the client from starting without even being selected, or drive
the engine into unbounded work or memory from a line in a properties file is a
vulnerability, and the loader is written against exactly that threat. A pack
that merely renders a wrong image is an ordinary bug and belongs in the
[issue tracker](https://github.com/avpbynf/Vitrail-Shaders/issues).

## Reporting a vulnerability

Report privately, through GitHub's own channel: the Security tab of this
repository, then "Report a vulnerability". If that route does not work for
you, mail vitrail-shaders@pm.me. Either way, do not put something exploitable
in a public issue before a fix is out.

This is a one-person project, so the honest promise is a modest one: an
acknowledgement within a few days, a verdict once the report has been
reproduced or refuted, and credit in the release notes if you want it.

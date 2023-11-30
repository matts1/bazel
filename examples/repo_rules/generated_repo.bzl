BUILD_FILE_CONTENT = """
sh_binary(
  name = "hello_world",
  srcs = ["hello_world.sh"],
)

exports_files(["out.txt"])
"""

HELLO_WORLD_CONTENT = """#!/bin/bash

echo "Hello World" > "$1"
"""

RepoInfo = provider(fields = {"files": "(depset[File]) The files output by the rule"})

def _generate_repo_impl(ctx):
    build = ctx.actions.declare_file("repo/BUILD.bazel")
    hello_world = ctx.actions.declare_file("repo/hello_world.sh")

    ctx.actions.write(build, content = BUILD_FILE_CONTENT)
    ctx.actions.write(hello_world, content = HELLO_WORLD_CONTENT, is_executable = True)

    f = ctx.actions.declare_file("repo/out.txt")
    args = ctx.actions.args()
    args.add(f)
    ctx.actions.run(
        outputs = [f],
        inputs = [hello_world],
        executable = hello_world,
        arguments = [args],
    )
    return [
        DefaultInfo(files = depset([build, hello_world, f])),
        RepoInfo(files = depset([build, hello_world, f])),
    ]

generate_repo = rule(
    implementation = _generate_repo_impl,
    attrs = dict(
    ),
    provides = [RepoInfo],
)

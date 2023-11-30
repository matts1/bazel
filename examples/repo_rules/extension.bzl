RepoInfo = provider(fields = {})

def _gen_repo_impl(module_ctx):
    generator = module_ctx.modules[0].tags.gen_repo[0].generator
    files = generator["//:generated_repo.bzl%RepoInfo"].files

    path_to_content = {}
    for f in files.to_list():
        if not f.short_path.startswith("repo/"):
            fail("Needs to start with a directory so we can declare BUILD.bazel")
        path = f.short_path[5:]
        content = module_ctx.read(f)
        path_to_content[path] = content
        print("Got file", path, "with content:\n" + content)

    simple_repo(
        name = "generated_repo",
        path_to_content = path_to_content,
    )

_gen_repo = tag_class(
    attrs = dict(
        # At the moment the providers aren't checked
        generator = attr.label(providers = [DefaultInfo, RepoInfo]),
        foo = attr.label(default = "//:bar"),
    ),
)

gen_repo = module_extension(
    implementation = _gen_repo_impl,
    tag_classes = {"gen_repo": _gen_repo},
)

def _simple_repo_impl(repo_ctx):
    for path, content in repo_ctx.attr.path_to_content.items():
        repo_ctx.file(path, content)

simple_repo = repository_rule(
    implementation = _simple_repo_impl,
    attrs = dict(
        path_to_content = attr.string_dict(),
    ),
)

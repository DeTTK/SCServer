"""Find launcher/Steam resource roots without changing the installed game."""
from pathlib import Path


def resolve_game(selected):
    path = Path(selected).expanduser().resolve()
    if path.is_file():
        path = path.parent
    candidates = []
    if path.name.lower() in ('bin_ru', 'bin_global'):
        candidates.append((path.parent, path.name))
    candidates.extend((path / child, variant)
                      for child in ('', 'game', 'client', 'contents')
                      for variant in ('', 'bin_ru', 'bin_global'))
    for root, variant in candidates:
        binary = root / variant
        patch = binary / 'patchassets'
        config = (patch if variant else root / 'modassets') / 'assets/bundle/configs.xeon'
        if ((root / 'assets').is_dir() and (root / 'modassets').is_dir()
                and (binary / 'classes.jar').is_file() and config.is_file()):
            return dict(root=root, selected=binary, variant=variant or 'launcher',
                        modassets=root / 'modassets', patchassets=patch if patch.is_dir() else None)
    raise ValueError('Choose the installed game root containing assets, modassets and bin_ru/bin_global (Steam), or classes.jar (launcher).')

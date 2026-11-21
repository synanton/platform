"""Deterministic, non-normalizing surface anchoring for model-proposed mentions."""
from __future__ import annotations

def anchor_surface(chunk_text, surface, *, model_start=None, model_end=None,
                   context=None, chunk_start=0):
    """Resolve an exact surface occurrence; never shift or normalize it.

    Returns {status, surface, chunk_char_start/end, char_start/end,
    model_char_start/end, reason}. Document offsets come only from chunk_start.
    """
    if not surface:
        return _reject(surface, model_start, model_end, 'SURFACE_NOT_FOUND')
    positions = []
    start = 0
    while True:
        i = chunk_text.find(surface, start)
        if i < 0:
            break
        positions.append(i)
        start = i + 1
    if not positions:
        return _reject(surface, model_start, model_end, 'SURFACE_NOT_FOUND')
    if len(positions) == 1:
        return _resolve(chunk_text, surface, positions[0], chunk_start, model_start, model_end)
    if context:
        contained = []
        cstart = 0
        while True:
            c = chunk_text.find(context, cstart)
            if c < 0:
                break
            cend = c + len(context)
            for p in positions:
                if c <= p and p + len(surface) <= cend:
                    contained.append(p)
            cstart = c + 1
        contained = sorted(set(contained))
        if len(contained) == 1:
            return _resolve(chunk_text, surface, contained[0], chunk_start, model_start, model_end)
    return _reject(surface, model_start, model_end, 'AMBIGUOUS_SURFACE')

def _resolve(text, surface, local_start, chunk_start, model_start, model_end):
    local_end = local_start + len(surface)
    return {
        'status': 'RESOLVED', 'surface': surface,
        'chunk_char_start': local_start, 'chunk_char_end': local_end,
        'char_start': chunk_start + local_start, 'char_end': chunk_start + local_end,
        'model_char_start': model_start, 'model_char_end': model_end,
        'reason': None,
    }

def _reject(surface, model_start, model_end, reason):
    return {
        'status': 'REJECTED', 'surface': surface,
        'chunk_char_start': None, 'chunk_char_end': None,
        'char_start': None, 'char_end': None,
        'model_char_start': model_start, 'model_char_end': model_end,
        'reason': reason,
    }

-- Prompt registry: named templates, immutable versions, mutable aliases.
CREATE TABLE prompt (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE prompt_version (
    id UUID PRIMARY KEY,
    prompt_id UUID NOT NULL REFERENCES prompt(id),
    version INT NOT NULL,
    template JSONB NOT NULL,                  -- message array with {{variables}}
    variables TEXT[] NOT NULL,
    model_defaults JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (prompt_id, version)
);

CREATE TABLE prompt_alias (
    prompt_id UUID NOT NULL REFERENCES prompt(id),
    alias TEXT NOT NULL,                      -- production | staging
    version INT NOT NULL,
    PRIMARY KEY (prompt_id, alias)
);

#!/bin/bash
# Adds config/solr/schema-additions.xml to a Solr schema.xml: the field type before the platform's first
# fieldType, the dynamic field before its first dynamicField. Idempotent; keeps schema.xml.orig on first run.
#   scripts/apply-solr-schema.sh hybris/config/solr/instances/default/configsets/default/conf/schema.xml
set -euo pipefail
schema=${1:?path to schema.xml}
additions="$(dirname "$0")/../config/solr/schema-additions.xml"
if grep -q 'name="knn_vector_1024"' "$schema"; then echo "already applied: $schema"; exit 0; fi
[ -f "$schema.orig" ] || cp "$schema" "$schema.orig"
python3 - "$schema" "$additions" <<'PY'
import re, sys
schema_path, additions_path = sys.argv[1:]
schema, additions = open(schema_path, encoding="utf-8").read(), open(additions_path, encoding="utf-8").read()
field_type = re.search(r'<fieldType name="knn_vector_1024".*?/>', additions, re.S).group(0)
dynamic_field = re.search(r'<dynamicField name="\*_knn1024".*?/>', additions, re.S).group(0)
for anchor, element in (("<fieldType ", field_type), ("<dynamicField ", dynamic_field)):
    i = schema.index(anchor)
    indent = schema[schema.rindex("\n", 0, i) + 1:i]
    schema = schema[:i] + element + "\n" + indent + schema[i:]
open(schema_path, "w", encoding="utf-8").write(schema)
PY
echo "applied: $schema"

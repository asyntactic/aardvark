ID=$1
USER=$2
PW=$3
OUTPUT=$4

mysqldump -u $USER -p $PW aardvark domains --no-create-info --complete-insert --where="id='$ID'" > $OUTPUT
mysqldump -u $USER -p $PW aardvark entities roles semantic_types modifiers contexts modifier_values schemae tables columns datastores semantic_functions relational_functions formulas conversions --no-create-info --complete-insert --where="domain_id='$ID'" >> $OUTPUT

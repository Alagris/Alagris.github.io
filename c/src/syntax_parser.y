%{
	#include <stdio.h>
	#include <stdlib.h>
	#include "AST.h"

  extern int yylex();
  extern int yyparse();
  extern FILE *yyin;
//  extern int yydebug=1;
 
  void yyerror(const char *s);

%}


%union {
  char *sstring;
  LiteralList *sstrings;
  FSAKleeneClousure *sFSAKleeneClousure;
  FSA *sFSA;
  FSAConcat *sFSAConcat;
  FSAUnion *sFSAUnion;
  InputExpression *sInputExpression;
  MealyAtomic *sMealyAtomic;
  MealyConcat *sMealyConcat;
  MealyUnion *sMealyUnion;
  char schar;
}

%token COLON
%token L_PARENTHESIS
%token R_PARENTHESIS
%token PIPE
%token LT
%token GT
%token DASH
%token EQUALS
%token BACK_SLASH
%token NEW_LINE
%token S_QUOTE
%token S_APOSTROPHE
%token EOL
%token ASTERIKS
%token COMMA
%token R_R_BRACKET
%token R_BACK_SLASH
%token R_DASH
%token R_DASH_CHAR
%token ALPHABET_OP
%token JUDGEMENTS_OP
%token <schar> R_HEX_CHAR
%token <schar> R_CHAR
%token <schar> HEX_CHAR
%token <sstring> STRING
%token <sFSA> ID
%token <sstring> TEMPORAL_OPERATOR
%type <schar> escaped_char
%type <schar> range_literal
%type <sstring> string_atomic
%type <sstrings> string_literal
%type <sFSAKleeneClousure> fsa_Kleene_clousure
%type <sFSA> input_atomic
%type <sFSA> function
%type <sFSA> range
%type <sFSA> fsa
%type <sFSAConcat> fsa_concat
%type <sFSAUnion> fsa_union
%type <sInputExpression> input_expression
%type <sMealyAtomic> mealy_atomic
%type <sMealyAtomic> mealy_Kleene_closure
%type <sMealyConcat> mealy_concat
%type <sMealyUnion> mealy_union
%type <sMealyUnion> mealy

%%
defs
	: defs def
	| def
	;

def
	: ID EQUALS mealy {
			printf("%s: OK\n", $1);
			free($1);
		}
	| ID L_PARENTHESIS params R_PARENTHESIS EQUALS mealy {
			printf("%s: OK\n", $1);
			free($1);
		}
	| ID ALPHABET_OP range {
			printf("%s: OK\n", $1);
			free($1);
		}
	| ID ALPHABET_OP enum_alphabet {
			printf("%s: OK\n", $1);
			free($1);
		}
	| ID COLON judgements {
			printf("%s: OK\n", $1);
			free($1);
		}
	| EOL
	| /* empty */
	;

judgements
	: judgements JUDGEMENTS_OP ID
	| ID
	;

params
	: params COMMA ID
	| ID
	;

enum_alphabet
	: enum_alphabet range_literal
	| range_literal
	;

mealy
	: mealy_union
	;

mealy_union
	: mealy_union PIPE mealy_concat {
			// addToMealyUnion((MealyUnion *) $$, (MealyConcat *) $3);
		}
	| mealy_concat {
			// $$ = createMealyUnion((MealyConcat *) $1);
		}
	;

mealy_concat
	: mealy_concat mealy_Kleene_closure {
			// addToMealyConcat((MealyConcat *) $$, (MealyAtomic *) $2);
		}
	| mealy_Kleene_closure {
			// $$ = createMealyConcat((MealyAtomic *) $1);
		}
	;

mealy_Kleene_closure
	: L_PARENTHESIS mealy_atomic R_PARENTHESIS ASTERIKS {
			// $$ = setMealyAtomicClousure((MealyAtomic *) $2, MEALY_ATOMIC_KLEENE_CLOUSURE_CLOSED);
		}
	| mealy_atomic {
			// $$ = setMealyAtomicClousure((MealyAtomic *) $1, MEALY_ATOMIC_KLEENE_CLOUSURE_OPENED);
		}
	;

mealy_atomic
	: input_expression COLON string_literal {
			// $$ = createMealyAtomic((InputExpression *) $1, (LiteralList *) $3);
		}
	| input_expression {
			// $$ = createMealyAtomic((InputExpression *) $1, (LiteralList *) NULL);
		}
	;

input_expression
	: input_expression input_atomic {
			// addToInputExpression((InputExpression *) $$, $2);
		}
	| input_atomic {
			// $$ = createInputExpression($1);
		}
	;

input_atomic
	: ID { $$ = createMockFSA(); }
	| function { $$ = createMockFSA(); }
	| temporal_expression { $$ = createMockFSA(); }
	| fsa
	| range { $$ = createMockFSA(); }
	;

range
	: range_literal R_DASH range_literal
	;

range_literal
	: R_HEX_CHAR
	| R_R_BRACKET { $$ = ']'; }
	| R_DASH_CHAR { $$ = '-'; }
	| R_BACK_SLASH { $$ = '\\'; }
	| R_CHAR
	;

function
	: ID L_PARENTHESIS param_values R_PARENTHESIS
	;

temporal_expression
	: TEMPORAL_OPERATOR L_PARENTHESIS param_values R_PARENTHESIS
	;

param_values
	: param_values COMMA input_atomic
	| input_atomic
	;

fsa
	: string_literal { 
			// $$ = createFSAWithLiteral((LiteralList *) $1);
		}
	| L_PARENTHESIS fsa_union R_PARENTHESIS {
			// $$ = createFSAWithUnion($2);
		}
	;

fsa_union
	: fsa_union PIPE fsa_concat {
			// addToFSAUnion((FSAUnion *) $$, $3);
		}
	| fsa_concat {
			// $$ = createFSAUnion($1);
		}
	;

fsa_concat
	: fsa_concat fsa_Kleene_clousure {
			// addToFSAConcat(((FSAConcat *) $$), $2);
		}
	| fsa_Kleene_clousure {
			// $$ = createFSAConcat($1);
		}
	;

fsa_Kleene_clousure
	: L_PARENTHESIS input_atomic R_PARENTHESIS ASTERIKS {
			// $$ = createKleeneClousure($2, FSA_KLEENE_CLOUSURE_CLOSED);
		}
	| input_atomic {
			// $$ = createKleeneClousure($1, FSA_KLEENE_CLOUSURE_OPENED);
		}
	;

string_literal
	: string_literal string_atomic {
			addToLiteralList(((LiteralList *) $$), $2);
		}
	| string_atomic {
			$$ = createLiteralList($1);
		}
	;

string_atomic
	:	STRING
	| escaped_char { 
			$$ = (char *) malloc(sizeof(char));
			*($$) = $1;
		}
	;

escaped_char
	: NEW_LINE { $$ = '\n'; }
	| BACK_SLASH { $$ = '\\'; }
	| S_QUOTE { $$ = '\"'; }
	| S_APOSTROPHE { $$ = '\''; }
	| HEX_CHAR
	;
%%

void yyerror(const char *s) {
	printf("Parse error!  Message: %s\n", s );
	exit(-1);
}

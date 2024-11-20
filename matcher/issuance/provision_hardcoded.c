#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <stdint.h>
#include <unistd.h>
#include "../cJSON/cJSON.h"
#include "../credentialmanager.h"

#include "aus_card_icon.h"

#define Matcher_NONE (0)
#define Matcher_EQUAL (1 << 0)

typedef struct Matcher {
    char *name;
    int type;
    struct cJSON *matcher_value;
    struct Matcher *next;
} Matcher;

static void init_matcher(Matcher *curr, char *name, int type, cJSON *matcher_value, Matcher *next) {
    curr->name = name;
    curr->type = type;
    curr->matcher_value = matcher_value;
    curr->next = next;
}

typedef struct MatchersPerDoc {
    char *doc_type;
    struct Matcher *head_matcher;
    int matcher_size;
    struct MatchersPerDoc *next;
} MatchersPerDoc;

static void init_per_doc_matchers(MatchersPerDoc *it) {
    it->doc_type = NULL;
    it->head_matcher = malloc(sizeof(Matcher));
    it->matcher_size = 0;
    it->next = NULL;
}

int main() {
	printf("Identity Credentials Matcher\n");
    printf("Adding hardcoded entry\n");
    char *icon_start = _wasm_sample_matchers_aus_mdl_cardart_png;

    AddStringIdEntry("0", icon_start, sizeof(_wasm_sample_matchers_aus_mdl_cardart_png), "CMWallet", "Save your document to CMWallet", NULL, NULL);

	return 0;
}
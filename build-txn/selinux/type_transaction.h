/* SPDX-License-Identifier: GPL-2.0-only */
/* 5.4 private-policy type addition. On failure the policy remains destructible;
 * callers must discard the whole transaction, not continue mutating it.
 */
static bool ksu_add_type_private(struct policydb *db, const char *name, bool attr)
{
    struct type_datum *type = symtab_search(&db->p_types, name);
    struct ebitmap *maps = NULL;
    struct type_datum **types = NULL;
    char **names = NULL, *key = NULL;
    u32 old_count = db->p_types.nprim, value;
    unsigned int i;

    if (type)
        return !!type->attribute == attr;
    if (old_count >= 0xffff) /* avtab type IDs are u16 */
        return false;
    value = old_count + 1;
    type = kzalloc(sizeof(*type), GFP_KERNEL);
    key = kstrdup(name, GFP_KERNEL);
    maps = kvcalloc(value, sizeof(*maps), GFP_KERNEL);
    types = kvmalloc_array(value, sizeof(*types), GFP_KERNEL);
    names = kvmalloc_array(value, sizeof(*names), GFP_KERNEL);
    if (!type || !key || !maps || !types || !names)
        goto fail;
    memcpy(maps, db->type_attr_map_array, old_count * sizeof(*maps));
    memcpy(types, db->type_val_to_struct, old_count * sizeof(*types));
    memcpy(names, db->sym_val_to_name[SYM_TYPES], old_count * sizeof(*names));
    if (ebitmap_set_bit(&maps[old_count], old_count, 1))
        goto fail;
    type->primary = 1;
    type->value = value;
    type->attribute = attr;
    types[old_count] = type;
    names[old_count] = key;
    if (symtab_insert(&db->p_types, key, type))
        goto fail;

    /* No failing operation between insertion and ownership transfer.
     * Existing bitmaps are moved with the array, not destroyed.
     */
    kvfree(db->type_attr_map_array);
    kvfree(db->type_val_to_struct);
    kvfree(db->sym_val_to_name[SYM_TYPES]);
    db->type_attr_map_array = maps;
    db->type_val_to_struct = types;
    db->sym_val_to_name[SYM_TYPES] = names;
    db->p_types.nprim = value;
    for (i = 0; i < db->p_roles.nprim; i++) {
        /* policydb_read recreates object_r and discards its serialized role
         * datum. It does not use a type allow-list; keep its bitmap canonical.
         */
        if (db->role_val_to_struct[i]->value == OBJECT_R_VAL)
            continue;
        if (ebitmap_set_bit(&db->role_val_to_struct[i]->types, old_count, 1))
            return false;
    }
    return true;
fail:
    /* Only the new bitmap belongs to us before commit. */
    if (maps)
        ebitmap_destroy(&maps[old_count]);
    kvfree(maps);
    kvfree(types);
    kvfree(names);
    kfree(key);
    kfree(type);
    return false;
}

/** One schema entry in the object transfer: a whole schema or an explicit table list. */
export interface SelectionEntry {
  schema: string
  /** true = whole schema selected, false = only the tables listed here. */
  all: boolean
  tables: string[]
}

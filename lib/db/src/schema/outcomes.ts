import { pgTable, serial, text, integer, numeric, timestamp, index } from "drizzle-orm/pg-core";
import { createInsertSchema } from "drizzle-zod";
import { z } from "zod/v4";
import { marketsTable } from "./markets";

export const outcomesTable = pgTable("outcomes", {
  id: serial("id").primaryKey(),
  marketId: integer("market_id").notNull().references(() => marketsTable.id, { onDelete: "cascade" }),
  name: text("name").notNull(),
  odds: numeric("odds", { precision: 10, scale: 4 }).notNull(),
  updatedAt: timestamp("updated_at").notNull().defaultNow(),
}, (t) => [
  index("outcomes_market_id_idx").on(t.marketId),
]);

export const insertOutcomeSchema = createInsertSchema(outcomesTable).omit({ id: true, updatedAt: true });
export type InsertOutcome = z.infer<typeof insertOutcomeSchema>;
export type Outcome = typeof outcomesTable.$inferSelect;

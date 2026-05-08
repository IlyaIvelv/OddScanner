import { pgTable, serial, integer, numeric, text, jsonb, timestamp, boolean, index } from "drizzle-orm/pg-core";
import { createInsertSchema } from "drizzle-zod";
import { z } from "zod/v4";
import { eventsTable } from "./events";
import { type MarketType } from "./markets";

export type ArbLeg = {
  bookmakerId: number;
  bookmakerName: string;
  marketId: number;
  marketName: string;
  outcomeName: string;
  odds: number;
  stakePercent: number;
};

export const arbsTable = pgTable("arbs", {
  id: serial("id").primaryKey(),
  eventId: integer("event_id").notNull().references(() => eventsTable.id),
  marketType: text("market_type").$type<MarketType>().notNull(),
  profitPct: numeric("profit_pct", { precision: 8, scale: 4 }).notNull(),
  legs: jsonb("legs").$type<ArbLeg[]>().notNull(),
  isActive: boolean("is_active").notNull().default(true),
  foundAt: timestamp("found_at").notNull().defaultNow(),
  expiresAt: timestamp("expires_at"),
}, (t) => [
  index("arbs_event_id_idx").on(t.eventId),
  index("arbs_found_at_idx").on(t.foundAt),
  index("arbs_is_active_idx").on(t.isActive),
]);

export const insertArbSchema = createInsertSchema(arbsTable).omit({ id: true, foundAt: true });
export type InsertArb = z.infer<typeof insertArbSchema>;
export type Arb = typeof arbsTable.$inferSelect;

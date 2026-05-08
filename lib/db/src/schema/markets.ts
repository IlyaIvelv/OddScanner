import { pgTable, serial, text, integer, timestamp, index } from "drizzle-orm/pg-core";
import { createInsertSchema } from "drizzle-zod";
import { z } from "zod/v4";
import { bookmakersTable } from "./bookmakers";
import { eventsTable } from "./events";

export const MARKET_TYPES = [
  "1X2",
  "TOTAL_OVER_UNDER",
  "HANDICAP",
  "CORNERS_1X2",
  "CORNERS_TOTAL",
  "CARDS_TOTAL",
  "BOTH_TEAMS_TO_SCORE",
  "DOUBLE_CHANCE",
] as const;

export type MarketType = (typeof MARKET_TYPES)[number];

export const marketsTable = pgTable("markets", {
  id: serial("id").primaryKey(),
  bookmakerId: integer("bookmaker_id").notNull().references(() => bookmakersTable.id),
  eventId: integer("event_id").notNull().references(() => eventsTable.id),
  type: text("type").$type<MarketType>().notNull(),
  name: text("name").notNull(),
  line: text("line"),
  updatedAt: timestamp("updated_at").notNull().defaultNow(),
}, (t) => [
  index("markets_event_id_idx").on(t.eventId),
  index("markets_bookmaker_event_idx").on(t.bookmakerId, t.eventId),
]);

export const insertMarketSchema = createInsertSchema(marketsTable).omit({ id: true, updatedAt: true });
export type InsertMarket = z.infer<typeof insertMarketSchema>;
export type Market = typeof marketsTable.$inferSelect;

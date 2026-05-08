import { pgTable, serial, integer, text, timestamp, index } from "drizzle-orm/pg-core";
import { createInsertSchema } from "drizzle-zod";
import { z } from "zod/v4";
import { bookmakersTable } from "./bookmakers";

export const SCRAPE_JOB_STATUSES = ["pending", "running", "success", "failed"] as const;
export type ScrapeJobStatus = (typeof SCRAPE_JOB_STATUSES)[number];

export const scrapeJobsTable = pgTable("scrape_jobs", {
  id: serial("id").primaryKey(),
  bookmakerId: integer("bookmaker_id").notNull().references(() => bookmakersTable.id),
  status: text("status").$type<ScrapeJobStatus>().notNull().default("pending"),
  eventsFound: integer("events_found").default(0),
  marketsFound: integer("markets_found").default(0),
  error: text("error"),
  startedAt: timestamp("started_at").notNull().defaultNow(),
  finishedAt: timestamp("finished_at"),
}, (t) => [
  index("scrape_jobs_bookmaker_idx").on(t.bookmakerId),
  index("scrape_jobs_started_at_idx").on(t.startedAt),
]);

export const insertScrapeJobSchema = createInsertSchema(scrapeJobsTable).omit({ id: true, startedAt: true });
export type InsertScrapeJob = z.infer<typeof insertScrapeJobSchema>;
export type ScrapeJob = typeof scrapeJobsTable.$inferSelect;

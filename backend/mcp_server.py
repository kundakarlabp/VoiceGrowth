from __future__ import annotations

import os
from typing import Any

from mcp.server import MCPServer
from mcp.types import ToolAnnotations
from sqlalchemy import or_, select

from app import Consult, SessionLocal
from clinical_app import ConsultLink, Encounter, OutcomeUpdate, Patient, fact_values, outcome_dict

READ_ONLY = ToolAnnotations(read_only_hint=True, open_world_hint=False)

mcp = MCPServer(
    "voicegrowth-clinical",
    instructions=(
        "Private Infectious Diseases consult archive. Use CR number when available for patient identity. "
        "Treat transcript-derived facts, clinician opinions, and AI interpretation as distinct. Prefer verified "
        "records for research conclusions and disclose when a record is only AI-extracted."
    ),
)


def consult_summary(c: Consult) -> dict[str, Any]:
    return {
        "consult_id": c.id,
        "patient_name": c.patient_name,
        "cr_number": c.cr_number,
        "consult_date": c.consult_date,
        "department": c.admitting_department,
        "consult_for": c.consult_for,
        "primary_team_diagnosis": c.primary_team_diagnosis,
        "id_working_diagnosis": c.id_working_diagnosis,
        "summary": c.consult_summary,
        "verification_status": c.verification_status,
    }


@mcp.tool(
    title="Search clinical consults",
    description="Use this when the user wants to find prior VoiceGrowth consults by patient name, CR number, department, consult reason or diagnosis.",
    annotations=READ_ONLY,
)
def search_consults(query: str, limit: int = 20) -> dict[str, Any]:
    limit = max(1, min(limit, 100))
    like = f"%{query.strip()}%"
    with SessionLocal() as db:
        rows = db.scalars(
            select(Consult).where(or_(
                Consult.patient_name.ilike(like),
                Consult.cr_number.ilike(like),
                Consult.admitting_department.ilike(like),
                Consult.consult_for.ilike(like),
                Consult.primary_team_diagnosis.ilike(like),
                Consult.id_working_diagnosis.ilike(like),
                Consult.consult_summary.ilike(like),
            )).order_by(Consult.id.desc()).limit(limit)
        ).all()
        return {"count": len(rows), "consults": [consult_summary(c) for c in rows]}


@mcp.tool(
    title="Get clinical consult",
    description="Use this when the user wants the complete structured VoiceGrowth record and transcripts for a known consult ID.",
    annotations=READ_ONLY,
)
def get_consult(consult_id: int, include_transcripts: bool = True) -> dict[str, Any]:
    with SessionLocal() as db:
        c = db.get(Consult, consult_id)
        if not c:
            return {"error": "Consult not found", "consult_id": consult_id}
        outcomes = db.scalars(
            select(OutcomeUpdate).where(OutcomeUpdate.consult_id == consult_id).order_by(OutcomeUpdate.recorded_at)
        ).all()
        result = consult_summary(c)
        result["structured_record"] = c.structured_record
        result["outcomes"] = [outcome_dict(x) for x in outcomes]
        if include_transcripts:
            result["source_transcript"] = c.recording.source_transcript
            result["corrected_transcript"] = c.recording.corrected_transcript
        return result


@mcp.tool(
    title="Get patient timeline",
    description="Use this when the user wants the longitudinal consult history for a known CR number, including admissions and recorded outcomes.",
    annotations=READ_ONLY,
)
def get_patient_timeline(cr_number: str) -> dict[str, Any]:
    normalized = "".join(ch for ch in cr_number.upper() if ch.isalnum())
    with SessionLocal() as db:
        patient = db.scalar(select(Patient).where(Patient.cr_normalized == normalized))
        if not patient:
            return {"error": "Patient not found", "cr_number": cr_number}
        rows = db.execute(
            select(Consult, ConsultLink, Encounter)
            .join(ConsultLink, ConsultLink.consult_id == Consult.id)
            .outerjoin(Encounter, Encounter.id == ConsultLink.encounter_id)
            .where(ConsultLink.patient_id == patient.id)
            .order_by(Consult.id)
        ).all()
        timeline = []
        for consult, link, encounter in rows:
            outcomes = db.scalars(
                select(OutcomeUpdate).where(OutcomeUpdate.consult_id == consult.id).order_by(OutcomeUpdate.recorded_at)
            ).all()
            item = consult_summary(consult)
            item["date_of_admission"] = encounter.date_of_admission if encounter else consult.date_of_admission
            item["outcomes"] = [outcome_dict(x) for x in outcomes]
            timeline.append(item)
        return {
            "patient": {
                "patient_id": patient.id,
                "patient_name": patient.patient_name,
                "cr_number": patient.cr_number,
                "age": patient.age,
                "sex": patient.sex,
            },
            "timeline": timeline,
        }


@mcp.tool(
    title="List pending consult follow-ups",
    description="Use this when the user wants pending tests, planned reviews or unresolved follow-up items from recent VoiceGrowth consults.",
    annotations=READ_ONLY,
)
def pending_followups(limit: int = 50) -> dict[str, Any]:
    limit = max(1, min(limit, 200))
    with SessionLocal() as db:
        rows = db.scalars(select(Consult).order_by(Consult.id.desc()).limit(1000)).all()
        result = []
        for c in rows:
            record = c.structured_record or {}
            pending = fact_values(record.get("pending_items"))
            followup = fact_values(record.get("follow_up_plan"))
            if pending or followup:
                item = consult_summary(c)
                item.update({"pending_items": pending, "follow_up_plan": followup})
                result.append(item)
            if len(result) >= limit:
                break
        return {"count": len(result), "followups": result}


@mcp.tool(
    title="Review recurring learning topics",
    description="Use this when the user wants to identify recurring knowledge gaps or clinical concepts from recent VoiceGrowth consults.",
    annotations=READ_ONLY,
)
def learning_topics(limit: int = 200) -> dict[str, Any]:
    limit = max(1, min(limit, 1000))
    with SessionLocal() as db:
        rows = db.scalars(select(Consult).order_by(Consult.id.desc()).limit(limit)).all()
        counts: dict[str, dict[str, Any]] = {}
        for c in rows:
            for topic in (c.structured_record or {}).get("learning_topics", []):
                text = str(topic).strip()
                if not text:
                    continue
                key = text.casefold()
                entry = counts.setdefault(key, {"topic": text, "count": 0, "consult_ids": []})
                entry["count"] += 1
                if len(entry["consult_ids"]) < 10:
                    entry["consult_ids"].append(c.id)
        topics = sorted(counts.values(), key=lambda x: (-x["count"], x["topic"].casefold()))
        return {"topics": topics}


@mcp.tool(
    title="Build research cohort",
    description="Use this when the user wants a candidate cohort from verified VoiceGrowth consults for audit, research or publication planning. This is discovery data, not an ethics-approved research export by itself.",
    annotations=READ_ONLY,
)
def research_cohort(query: str = "", department: str = "", verified_only: bool = True, limit: int = 500) -> dict[str, Any]:
    limit = max(1, min(limit, 5000))
    with SessionLocal() as db:
        stmt = select(Consult).order_by(Consult.id.desc()).limit(limit)
        if verified_only:
            stmt = stmt.where(Consult.verification_status == "verified")
        if department.strip():
            stmt = stmt.where(Consult.admitting_department.ilike(f"%{department.strip()}%"))
        if query.strip():
            like = f"%{query.strip()}%"
            stmt = stmt.where(or_(
                Consult.consult_for.ilike(like),
                Consult.primary_team_diagnosis.ilike(like),
                Consult.id_working_diagnosis.ilike(like),
                Consult.consult_summary.ilike(like),
            ))
        rows = db.scalars(stmt).all()
        records = []
        for c in rows:
            item = consult_summary(c)
            item["research_tags"] = (c.structured_record or {}).get("research_tags", [])
            item["learning_topics"] = (c.structured_record or {}).get("learning_topics", [])
            records.append(item)
        return {"count": len(records), "verified_only": verified_only, "records": records}


if __name__ == "__main__":
    host = os.getenv("MCP_HOST", "127.0.0.1")
    if host not in {"127.0.0.1", "localhost", "::1"} and os.getenv("MCP_ALLOW_INSECURE_REMOTE") != "true":
        raise RuntimeError(
            "Refusing unauthenticated remote MCP exposure. Keep MCP local/private until OAuth, mTLS, "
            "or an authenticated reverse proxy is configured."
        )
    port = int(os.getenv("MCP_PORT", "8001"))
    mcp.run(transport="streamable-http", host=host, port=port, json_response=True, stateless_http=True)

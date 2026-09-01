from __future__ import annotations

import asyncio
import html
import os
import re
import secrets
from datetime import datetime, timezone
from typing import Any

from fastapi import Depends, HTTPException, Query
from fastapi.responses import HTMLResponse
from fastapi.security import HTTPBasic, HTTPBasicCredentials
from pydantic import BaseModel, Field
from sqlalchemy import DateTime, ForeignKey, Integer, String, Text, UniqueConstraint, func, or_, select
from sqlalchemy.orm import Mapped, mapped_column

from app import Base, Consult, Recording, SessionLocal, app, engine, require_token


class Patient(Base):
    __tablename__ = "patients"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    cr_number: Mapped[str] = mapped_column(String(120), unique=True, index=True)
    cr_normalized: Mapped[str] = mapped_column(String(120), unique=True, index=True)
    patient_name: Mapped[str | None] = mapped_column(String(255), nullable=True, index=True)
    age: Mapped[str | None] = mapped_column(String(80), nullable=True)
    sex: Mapped[str | None] = mapped_column(String(40), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))


class Encounter(Base):
    __tablename__ = "encounters"
    __table_args__ = (UniqueConstraint("patient_id", "admission_key", name="uq_patient_admission_key"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    patient_id: Mapped[int] = mapped_column(ForeignKey("patients.id", ondelete="CASCADE"), index=True)
    admission_key: Mapped[str] = mapped_column(String(400))
    date_of_admission: Mapped[str] = mapped_column(String(80))
    admitting_department: Mapped[str | None] = mapped_column(String(255), nullable=True, index=True)
    ward_unit: Mapped[str | None] = mapped_column(String(255), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))


class ConsultLink(Base):
    __tablename__ = "consult_links"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    consult_id: Mapped[int] = mapped_column(ForeignKey("consults.id", ondelete="CASCADE"), unique=True, index=True)
    patient_id: Mapped[int] = mapped_column(ForeignKey("patients.id", ondelete="CASCADE"), index=True)
    encounter_id: Mapped[int | None] = mapped_column(ForeignKey("encounters.id", ondelete="SET NULL"), nullable=True, index=True)
    linked_by: Mapped[str] = mapped_column(String(40), default="cr_number")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))


class OutcomeUpdate(Base):
    __tablename__ = "outcome_updates"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    consult_id: Mapped[int] = mapped_column(ForeignKey("consults.id", ondelete="CASCADE"), index=True)
    recorded_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), index=True)
    recommendation_acceptance: Mapped[str | None] = mapped_column(String(80), nullable=True)
    clinical_status: Mapped[str | None] = mapped_column(String(255), nullable=True)
    diagnostic_update: Mapped[str | None] = mapped_column(Text, nullable=True)
    microbiology_update: Mapped[str | None] = mapped_column(Text, nullable=True)
    antimicrobial_update: Mapped[str | None] = mapped_column(Text, nullable=True)
    disposition: Mapped[str | None] = mapped_column(String(120), nullable=True)
    length_of_stay_days: Mapped[int | None] = mapped_column(Integer, nullable=True)
    mortality: Mapped[str | None] = mapped_column(String(40), nullable=True)
    readmission: Mapped[str | None] = mapped_column(String(80), nullable=True)
    notes: Mapped[str | None] = mapped_column(Text, nullable=True)
    source: Mapped[str] = mapped_column(String(40), default="manual")


Base.metadata.create_all(engine)


def normalize_cr(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9]", "", value).upper()


def admission_key(date_of_admission: str, department: str | None) -> str:
    return f"{date_of_admission.strip().lower()}|{(department or '').strip().lower()}"


def link_consult(db, consult: Consult) -> ConsultLink | None:
    existing = db.scalar(select(ConsultLink).where(ConsultLink.consult_id == consult.id))
    if existing:
        return existing
    if not consult.cr_number or not consult.cr_number.strip():
        return None
    normalized = normalize_cr(consult.cr_number)
    if not normalized:
        return None

    patient = db.scalar(select(Patient).where(Patient.cr_normalized == normalized))
    if patient is None:
        patient = Patient(
            cr_number=consult.cr_number.strip(),
            cr_normalized=normalized,
            patient_name=consult.patient_name,
            age=consult.age,
            sex=consult.sex,
        )
        db.add(patient)
        db.flush()
    else:
        if consult.patient_name:
            patient.patient_name = consult.patient_name
        if consult.age:
            patient.age = consult.age
        if consult.sex:
            patient.sex = consult.sex
        patient.updated_at = datetime.now(timezone.utc)

    encounter = None
    if consult.date_of_admission and consult.date_of_admission.strip():
        key = admission_key(consult.date_of_admission, consult.admitting_department)
        encounter = db.scalar(
            select(Encounter).where(Encounter.patient_id == patient.id, Encounter.admission_key == key)
        )
        if encounter is None:
            encounter = Encounter(
                patient_id=patient.id,
                admission_key=key,
                date_of_admission=consult.date_of_admission.strip(),
                admitting_department=consult.admitting_department,
                ward_unit=consult.ward_unit,
            )
            db.add(encounter)
            db.flush()
        else:
            if consult.ward_unit:
                encounter.ward_unit = consult.ward_unit
            encounter.updated_at = datetime.now(timezone.utc)

    link = ConsultLink(
        consult_id=consult.id,
        patient_id=patient.id,
        encounter_id=encounter.id if encounter else None,
        linked_by="cr_number",
    )
    db.add(link)
    db.flush()
    return link


def reconcile_links_once() -> int:
    linked = 0
    with SessionLocal() as db:
        rows = db.scalars(
            select(Consult)
            .outerjoin(ConsultLink, ConsultLink.consult_id == Consult.id)
            .where(ConsultLink.id.is_(None), Consult.cr_number.is_not(None))
            .order_by(Consult.id)
            .limit(200)
        ).all()
        for consult in rows:
            if link_consult(db, consult):
                linked += 1
        db.commit()
    return linked


async def linker_loop() -> None:
    while True:
        try:
            await asyncio.to_thread(reconcile_links_once)
            await asyncio.sleep(10)
        except asyncio.CancelledError:
            raise
        except Exception:
            await asyncio.sleep(15)


_linker_task: asyncio.Task | None = None


@app.on_event("startup")
async def start_linker() -> None:
    global _linker_task
    await asyncio.to_thread(reconcile_links_once)
    _linker_task = asyncio.create_task(linker_loop())


@app.on_event("shutdown")
async def stop_linker() -> None:
    if _linker_task:
        _linker_task.cancel()


class OutcomeInput(BaseModel):
    recommendation_acceptance: str | None = Field(default=None, max_length=80)
    clinical_status: str | None = Field(default=None, max_length=255)
    diagnostic_update: str | None = None
    microbiology_update: str | None = None
    antimicrobial_update: str | None = None
    disposition: str | None = Field(default=None, max_length=120)
    length_of_stay_days: int | None = Field(default=None, ge=0, le=10000)
    mortality: str | None = Field(default=None, max_length=40)
    readmission: str | None = Field(default=None, max_length=80)
    notes: str | None = None
    source: str = Field(default="manual", max_length=40)


def outcome_dict(row: OutcomeUpdate) -> dict[str, Any]:
    return {
        "id": row.id,
        "consult_id": row.consult_id,
        "recorded_at": row.recorded_at,
        "recommendation_acceptance": row.recommendation_acceptance,
        "clinical_status": row.clinical_status,
        "diagnostic_update": row.diagnostic_update,
        "microbiology_update": row.microbiology_update,
        "antimicrobial_update": row.antimicrobial_update,
        "disposition": row.disposition,
        "length_of_stay_days": row.length_of_stay_days,
        "mortality": row.mortality,
        "readmission": row.readmission,
        "notes": row.notes,
        "source": row.source,
    }


@app.post("/v2/consults/{consult_id}/outcomes", dependencies=[Depends(require_token)])
def add_outcome(consult_id: int, payload: OutcomeInput) -> dict[str, Any]:
    with SessionLocal() as db:
        consult = db.get(Consult, consult_id)
        if not consult:
            raise HTTPException(status_code=404, detail="Consult not found")
        row = OutcomeUpdate(consult_id=consult_id, **payload.model_dump())
        db.add(row)
        db.commit()
        db.refresh(row)
        return outcome_dict(row)


@app.get("/v2/consults/{consult_id}/outcomes", dependencies=[Depends(require_token)])
def list_outcomes(consult_id: int) -> list[dict[str, Any]]:
    with SessionLocal() as db:
        if not db.get(Consult, consult_id):
            raise HTTPException(status_code=404, detail="Consult not found")
        rows = db.scalars(
            select(OutcomeUpdate).where(OutcomeUpdate.consult_id == consult_id).order_by(OutcomeUpdate.recorded_at)
        ).all()
        return [outcome_dict(row) for row in rows]


@app.get("/v2/patients/search", dependencies=[Depends(require_token)])
def search_patients(q: str = Query(min_length=1), limit: int = Query(50, ge=1, le=200)) -> list[dict[str, Any]]:
    like = f"%{q}%"
    normalized = normalize_cr(q)
    with SessionLocal() as db:
        rows = db.scalars(
            select(Patient).where(or_(
                Patient.patient_name.ilike(like),
                Patient.cr_number.ilike(like),
                Patient.cr_normalized.ilike(f"%{normalized}%"),
            )).order_by(Patient.updated_at.desc()).limit(limit)
        ).all()
        return [{
            "patient_id": p.id,
            "cr_number": p.cr_number,
            "patient_name": p.patient_name,
            "age": p.age,
            "sex": p.sex,
        } for p in rows]


@app.get("/v2/patients/{patient_id}/timeline", dependencies=[Depends(require_token)])
def patient_timeline(patient_id: int) -> dict[str, Any]:
    with SessionLocal() as db:
        patient = db.get(Patient, patient_id)
        if not patient:
            raise HTTPException(status_code=404, detail="Patient not found")
        entries = db.execute(
            select(Consult, ConsultLink, Encounter)
            .join(ConsultLink, ConsultLink.consult_id == Consult.id)
            .outerjoin(Encounter, Encounter.id == ConsultLink.encounter_id)
            .where(ConsultLink.patient_id == patient_id)
            .order_by(Consult.consult_date, Consult.id)
        ).all()
        timeline = []
        for consult, link, encounter in entries:
            outcomes = db.scalars(
                select(OutcomeUpdate).where(OutcomeUpdate.consult_id == consult.id).order_by(OutcomeUpdate.recorded_at)
            ).all()
            timeline.append({
                "consult_id": consult.id,
                "consult_date": consult.consult_date,
                "date_of_admission": encounter.date_of_admission if encounter else consult.date_of_admission,
                "department": consult.admitting_department,
                "ward_unit": consult.ward_unit,
                "consult_for": consult.consult_for,
                "primary_team_diagnosis": consult.primary_team_diagnosis,
                "id_working_diagnosis": consult.id_working_diagnosis,
                "summary": consult.consult_summary,
                "verification_status": consult.verification_status,
                "outcomes": [outcome_dict(x) for x in outcomes],
            })
        return {
            "patient": {
                "patient_id": patient.id,
                "cr_number": patient.cr_number,
                "patient_name": patient.patient_name,
                "age": patient.age,
                "sex": patient.sex,
            },
            "timeline": timeline,
        }


def fact_values(items: Any) -> list[str]:
    if not isinstance(items, list):
        return []
    values: list[str] = []
    for item in items:
        if isinstance(item, dict):
            value = item.get("value")
            if value:
                values.append(str(value))
    return values


@app.get("/v2/followups/pending", dependencies=[Depends(require_token)])
def pending_followups(limit: int = Query(100, ge=1, le=500)) -> list[dict[str, Any]]:
    with SessionLocal() as db:
        consults = db.scalars(select(Consult).order_by(Consult.id.desc()).limit(1000)).all()
        result = []
        for c in consults:
            record = c.structured_record or {}
            pending = fact_values(record.get("pending_items"))
            followup = fact_values(record.get("follow_up_plan"))
            if pending or followup:
                result.append({
                    "consult_id": c.id,
                    "cr_number": c.cr_number,
                    "patient_name": c.patient_name,
                    "consult_date": c.consult_date,
                    "department": c.admitting_department,
                    "pending_items": pending,
                    "follow_up_plan": followup,
                    "verification_status": c.verification_status,
                })
            if len(result) >= limit:
                break
        return result


@app.get("/v2/research/cohort", dependencies=[Depends(require_token)])
def research_cohort(
    q: str | None = None,
    department: str | None = None,
    verified_only: bool = True,
    limit: int = Query(500, ge=1, le=5000),
) -> dict[str, Any]:
    with SessionLocal() as db:
        stmt = select(Consult).order_by(Consult.id.desc()).limit(limit)
        if verified_only:
            stmt = stmt.where(Consult.verification_status == "verified")
        if department:
            stmt = stmt.where(Consult.admitting_department.ilike(f"%{department}%"))
        if q:
            like = f"%{q}%"
            stmt = stmt.where(or_(
                Consult.consult_for.ilike(like),
                Consult.primary_team_diagnosis.ilike(like),
                Consult.id_working_diagnosis.ilike(like),
                Consult.consult_summary.ilike(like),
            ))
        rows = db.scalars(stmt).all()
        return {
            "count": len(rows),
            "verified_only": verified_only,
            "records": [{
                "consult_id": c.id,
                "cr_number": c.cr_number,
                "patient_name": c.patient_name,
                "age": c.age,
                "sex": c.sex,
                "date_of_admission": c.date_of_admission,
                "department": c.admitting_department,
                "consult_date": c.consult_date,
                "consult_for": c.consult_for,
                "primary_team_diagnosis": c.primary_team_diagnosis,
                "id_working_diagnosis": c.id_working_diagnosis,
                "verification_status": c.verification_status,
                "research_tags": (c.structured_record or {}).get("research_tags", []),
                "learning_topics": (c.structured_record or {}).get("learning_topics", []),
            } for c in rows],
        }


@app.get("/v2/learning/topics", dependencies=[Depends(require_token)])
def learning_topics(limit: int = Query(100, ge=1, le=1000)) -> dict[str, Any]:
    with SessionLocal() as db:
        consults = db.scalars(select(Consult).order_by(Consult.id.desc()).limit(limit)).all()
        counts: dict[str, int] = {}
        examples: dict[str, list[int]] = {}
        for c in consults:
            for topic in (c.structured_record or {}).get("learning_topics", []):
                clean = str(topic).strip()
                if not clean:
                    continue
                key = clean.casefold()
                counts[key] = counts.get(key, 0) + 1
                examples.setdefault(key, []).append(c.id)
        ranked = sorted(counts, key=lambda x: (-counts[x], x))
        return {"topics": [{
            "topic": key,
            "count": counts[key],
            "consult_ids": examples[key][:10],
        } for key in ranked]}


basic = HTTPBasic(auto_error=False)


def require_dashboard(credentials: HTTPBasicCredentials | None = Depends(basic)) -> str:
    configured_user = os.getenv("DASHBOARD_USERNAME", "")
    configured_password = os.getenv("DASHBOARD_PASSWORD", "")
    if not configured_user or not configured_password:
        raise HTTPException(status_code=503, detail="Dashboard credentials are not configured")
    if credentials is None:
        raise HTTPException(status_code=401, detail="Authentication required", headers={"WWW-Authenticate": "Basic"})
    user_ok = secrets.compare_digest(credentials.username.encode(), configured_user.encode())
    pass_ok = secrets.compare_digest(credentials.password.encode(), configured_password.encode())
    if not (user_ok and pass_ok):
        raise HTTPException(status_code=401, detail="Invalid credentials", headers={"WWW-Authenticate": "Basic"})
    return credentials.username


@app.get("/dashboard", response_class=HTMLResponse)
def dashboard(_: str = Depends(require_dashboard), q: str = "") -> str:
    with SessionLocal() as db:
        total = db.scalar(select(func.count(Consult.id))) or 0
        verified = db.scalar(select(func.count(Consult.id)).where(Consult.verification_status == "verified")) or 0
        patients = db.scalar(select(func.count(Patient.id))) or 0
        pending_jobs = db.scalar(select(func.count(Recording.id)).where(Recording.status.in_(["queued", "processing"]))) or 0
        stmt = select(Consult).order_by(Consult.id.desc()).limit(75)
        if q.strip():
            like = f"%{q.strip()}%"
            stmt = stmt.where(or_(
                Consult.patient_name.ilike(like),
                Consult.cr_number.ilike(like),
                Consult.admitting_department.ilike(like),
                Consult.id_working_diagnosis.ilike(like),
                Consult.consult_summary.ilike(like),
            ))
        consults = db.scalars(stmt).all()

    rows = "".join(
        "<tr>"
        f"<td>{c.id}</td><td>{html.escape(c.patient_name or '')}</td><td>{html.escape(c.cr_number or '')}</td>"
        f"<td>{html.escape(c.consult_date or '')}</td><td>{html.escape(c.admitting_department or '')}</td>"
        f"<td>{html.escape(c.consult_for or '')}</td><td>{html.escape(c.id_working_diagnosis or '')}</td>"
        f"<td>{html.escape(c.verification_status)}</td>"
        "</tr>" for c in consults
    )
    safe_q = html.escape(q)
    return f"""<!doctype html>
<html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>
<title>VoiceGrowth Clinical</title>
<style>
body{{font-family:system-ui,sans-serif;margin:24px;background:#f7f7f7;color:#111}} .cards{{display:flex;gap:12px;flex-wrap:wrap;margin:16px 0}}
.card{{background:white;border:1px solid #ddd;border-radius:10px;padding:14px;min-width:150px}} table{{width:100%;border-collapse:collapse;background:white}}
th,td{{border-bottom:1px solid #ddd;padding:8px;text-align:left;vertical-align:top;font-size:13px}} th{{position:sticky;top:0;background:#eee}}
input{{padding:9px;width:min(420px,80vw)}} button{{padding:9px 14px}} .wrap{{overflow:auto;max-height:70vh;border:1px solid #ddd}}
</style></head><body>
<h1>VoiceGrowth Clinical</h1><form method='get'><input name='q' value='{safe_q}' placeholder='Name, CR, department, diagnosis'><button>Search</button></form>
<div class='cards'><div class='card'><b>{total}</b><br>Total consults</div><div class='card'><b>{patients}</b><br>Patients linked</div><div class='card'><b>{verified}</b><br>Verified</div><div class='card'><b>{pending_jobs}</b><br>Processing</div></div>
<div class='wrap'><table><thead><tr><th>ID</th><th>Patient</th><th>CR</th><th>Date</th><th>Department</th><th>Consult for</th><th>ID diagnosis</th><th>Status</th></tr></thead><tbody>{rows}</tbody></table></div>
<p>Operational dashboard contains identifiable clinical data. Keep access restricted.</p></body></html>"""
